package com.johnsnowlabs.nlp.embeddings

import java.io.File

import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.annotators.tokenizer.wordpiece.{BasicTokenizer, WordpieceEncoder}
import com.johnsnowlabs.nlp.pretrained.ResourceDownloader
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{IntArrayParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SparkSession}


class BertEmbeddings(override val uid: String) extends
  AnnotatorModel[BertEmbeddings]
  with WriteTensorflowModel
  with HasEmbeddings
{

  def this() = this(Identifiable.randomUID("BERT_EMBEDDINGS"))

  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")
  val batchSize = new IntParam(this, "batchSize", "Batch size. Large values allows faster processing but requires more memory.")

  val vocabulary: MapFeature[String, Int] = new MapFeature(this, "vocabulary")

  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")
  def setConfigProtoBytes(bytes: Array[Int]) = set(this.configProtoBytes, bytes)
  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  def setVocabulary(value: Map[String, Int]): this.type = set(vocabulary, value)

  def sentenceStartTokenId: Int = {
    $$(vocabulary)("[CLS]")
  }

  def sentenceEndTokenId: Int = {
    $$(vocabulary)("[SEP]")
  }

  setDefault(
    dimension -> 768,
    batchSize -> 5,
    maxSentenceLength -> 256
  )

  def setBatchSize(size: Int): this.type = set(batchSize, size)

  def setMaxSentenceLength(value: Int): this.type = set(maxSentenceLength, value)
  def getMaxSentenceLength: Int = $(maxSentenceLength)

  private var _model: Option[Broadcast[TensorflowBert]] = None

  def getModelIfNotSet: TensorflowBert = {
    _model.get.value
  }

  def setModelIfNotSet(spark: SparkSession, tensorflow: TensorflowWrapper): this.type = {
    if (_model.isEmpty) {

      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowBert(
          tensorflow,
          sentenceStartTokenId,
          sentenceEndTokenId,
          $(maxSentenceLength),
          configProtoBytes = getConfigProtoBytes
          )
        )
      )
    }

    this
  }
  def tokenize(sentences: Seq[Sentence]): Seq[WordpieceTokenizedSentence] = {
    val basicTokenizer = new BasicTokenizer($(caseSensitive))
    val encoder = new WordpieceEncoder($$(vocabulary))

    sentences.map { s =>
      val tokens = basicTokenizer.tokenize(s)
      val wordpieceTokens = tokens.flatMap(token => encoder.encode(token))
      WordpieceTokenizedSentence(wordpieceTokens)
    }
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = SentenceSplit.unpack(annotations)
    val tokenizedSentences = TokenizedWithSentence.unpack(annotations)

    val tokenized = tokenize(sentences)
    val withEmbeddings = getModelIfNotSet.calculateEmbeddings(tokenized, tokenizedSentences, $(caseSensitive))
    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  override def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension)))
  }

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes = Array(AnnotatorType.DOCUMENT, AnnotatorType.TOKEN)
  override val outputAnnotatorType: AnnotatorType = AnnotatorType.WORD_EMBEDDINGS

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(path, spark, getModelIfNotSet.tensorflow, "_bert", BertEmbeddings.tfFile, configProtoBytes = getConfigProtoBytes)
  }
}

trait PretrainedBertModel {
  def pretrained(name: String = "bert_uncased", lang: String = "en", remoteLoc: String = ResourceDownloader.publicLoc): BertEmbeddings =
    ResourceDownloader.downloadModel(BertEmbeddings, name, Option(lang), remoteLoc)
}

trait ReadBertTensorflowModel extends ReadTensorflowModel {
  this:ParamsAndFeaturesReadable[BertEmbeddings] =>

  override val tfFile: String = "bert_tensorflow"

  def readTensorflow(instance: BertEmbeddings, path: String, spark: SparkSession): Unit = {
    val tf = readTensorflowModel(path, spark, "_bert_tf")
    instance.setModelIfNotSet(spark, tf)
  }

  addReader(readTensorflow)

  def loadFromPython(folder: String, spark: SparkSession): BertEmbeddings = {
    val f = new File(folder)
    val vocab = new File(folder, "vocab.txt")
    require(f.exists, s"Folder ${folder} not found")
    require(f.isDirectory, s"File ${folder} is not folder")
    require(vocab.exists(), s"Vocabulary file vocab.txt not found in folder ${folder}")

    val wrapper = TensorflowWrapper.read(folder, zipped = false)

    val vocabResource = new ExternalResource(vocab.getAbsolutePath, ReadAs.LINE_BY_LINE, Map("format" -> "text"))
    val words = ResourceHelper.parseLines(vocabResource).zipWithIndex.toMap

    new BertEmbeddings()
      .setVocabulary(words)
      .setModelIfNotSet(spark, wrapper)
  }
}


object BertEmbeddings extends ParamsAndFeaturesReadable[BertEmbeddings]
  with PretrainedBertModel
  with ReadBertTensorflowModel

