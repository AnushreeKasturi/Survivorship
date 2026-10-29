package com.startupsurvival.web

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write
import com.startupsurvival.{FeatureExtractor, FinancialFeatureExtractor, NameMatcher, SentimentScorer}
import com.startupsurvival.models.{NewsRecord, StartupRecord}
import com.startupsurvival.spark.FeatureSets

/**
 * Serves the Astro site (web/dist) and a JSON API backed by the real
 * pipeline code - the sentiment scorer, name matcher, feature extractors and
 * the leak-free LR models from results/model_coefficients_leakfree.json -
 * plus the scheduled watchlist in Tracker.
 *
 *   sbt "runMain com.startupsurvival.web.Server"   # from scala/, after `npm run build` in web/
 */
object Server {

  private implicit val formats: Formats = DefaultFormats

  final case class Coefficient(name: String, coefficient: Double)
  final case class LrModel(intercept: Double, features: List[Coefficient]) {
    def probability(x: Array[Double]): Double = {
      require(x.length == features.length, s"expected ${features.length} features, got ${x.length}")
      1.0 / (1.0 + math.exp(-(intercept + features.map(_.coefficient).zip(x).map { case (w, v) => w * v }.sum)))
    }
  }
  /**
   * The live models. `structural`, when exported by ml.Benchmark, is the random
   * forest that beat logistic regression on the benchmark; it scores the
   * structural (no-news) probability and sets the decision threshold.
   */
  final case class Models(categories: List[String], countries: List[String], financialOnly: LrModel, financialSentiment: LrModel,
      structural: Option[Forest] = None) {
    val vocab = FeatureSets.Vocab(categories.toArray, countries.toArray)
    def structuralProbability(x: Array[Double]): Double = structural.fold(financialOnly.probability(x))(_.probability(x))
    def threshold: Double = structural.fold(0.5)(_.threshold)
    def structuralName: String = structural.fold("logistic regression")(f => s"random forest, ${f.trees.size} trees")

    /** Fails at startup if the JSON's feature order drifts from FeatureSets'. */
    def checkFeatureOrder(): Unit = {
      val safe = FeatureSets.safeFeatureNames(vocab).toList
      require(financialOnly.features.map(_.name) == safe, "financialOnly features do not match FeatureSets")
      require(financialSentiment.features.map(_.name) == safe ++ FeatureSets.CoverageFeatureNames ++ FeatureSets.ToneFeatureNames,
        "financialSentiment features do not match FeatureSets")
    }
  }

  /**
   * When to warn that a company is older than the model's world. The model's
   * weight on years since first funding is negative, so its score keeps
   * falling with age; past these bounds that says more about age than survival.
   */
  final case class Maturity(olderAfter: Double, establishedAfter: Double, source: String) {
    def describe(years: Double): Map[String, Any] = Map(
      "yearsSinceFirstFunding" -> years,
      "level" -> (if (years >= establishedAfter) "established" else if (years >= olderAfter) "older" else "young"),
      "olderAfter" -> olderAfter,
      "establishedAfter" -> establishedAfter,
      "source" -> source
    )
  }
  object Maturity {
    val Design = Maturity(5, 10, "design rule: 5 and 10 years after the first round")

    /** The 90th and 99th percentiles from results/feature_ranges.json when spark.FeatureRanges has been run. */
    def load(path: String): Maturity =
      scala.util.Try {
        val ranges = parse(new String(Files.readAllBytes(Paths.get(path)), UTF_8)) \ "yearsSinceFirstFunding"
        val p90 = (ranges \ "p90").extract[Double]
        val p99 = (ranges \ "p99").extract[Double]
        Maturity(p90, p99, f"training data: 90%% of startups were within $p90%.1f years of their first round, 99%% within $p99%.1f")
      }.getOrElse(Design)
  }

  final case class Headline(date: String, text: String)
  final case class PredictRequest(
      foundedAt: String,
      firstFundingAt: String,
      categories: Option[List[String]],
      country: Option[String],
      headlines: Option[List[Headline]]
  )
  final case class MatchRequest(name: String, headline: String)
  final case class SentimentRequest(headline: String)

  private val MaxBodyBytes = 64 * 1024
  private val MaxHeadlines = 50

  private def date(field: String, raw: String): LocalDate =
    scala.util.Try(LocalDate.parse(raw.trim)).getOrElse(throw new IllegalArgumentException(s"$field must be YYYY-MM-DD"))

  private def sentiment(headline: String) = {
    val b = SentimentScorer.scoreHeadline(headline)
    Map(
      "score" -> b.sentimentScore,
      "class" -> b.sentimentClass.toString,
      "positiveTerms" -> b.matchedPositiveTerms,
      "negativeTerms" -> b.matchedNegativeTerms
    )
  }

  /**
   * Live predictions use today as the cutoff: the same feature code as
   * training, just "as of now" instead of 2013-01-01. Only the leak-free
   * inputs are asked for, so no lifetime funding totals.
   */
  def predict(req: PredictRequest, models: Models, cutoff: LocalDate = LocalDate.now(), bounds: Maturity = Maturity.Design): Map[String, Any] = {
    val founded = date("foundedAt", req.foundedAt)
    val firstFunding = date("firstFundingAt", req.firstFundingAt)
    require(founded.isBefore(cutoff), s"foundedAt must be before $cutoff")
    require(!firstFunding.isBefore(founded) && firstFunding.isBefore(cutoff), s"firstFundingAt must be between foundedAt and $cutoff")
    val categories = req.categories.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
    require(categories.length <= 50 && categories.forall(c => c.length <= 100 && !c.contains('|')), "invalid categories")
    val headlines = req.headlines.getOrElse(Nil).filter(_.text.trim.nonEmpty)
    require(headlines.length <= MaxHeadlines, s"at most $MaxHeadlines headlines")

    val financial = financialFeatures(founded, firstFunding, categories, req.country, cutoff)
    val scored = headlines.map { h =>
      SentimentScorer.scoreNews(NewsRecord(h.text, Some(date("headline date", h.date)), None, None, cutoff, Some("/demo"), 1.0, None))
    }
    val sentimentFeatures = FeatureExtractor.extractSentimentFeatures("/demo", scored, cutoff)
    val safe = FeatureSets.financialSafeArray(models.vocab)(financial)
    val withNews = safe ++ FeatureSets.coverageArray(sentimentFeatures) ++ FeatureSets.toneArray(sentimentFeatures)

    Map(
      "financialOnly" -> models.structuralProbability(safe),
      "threshold" -> models.threshold,
      "model" -> models.structuralName,
      "financialSentiment" -> (if (scored.isEmpty) None else Some(models.financialSentiment.probability(withNews))),
      "financial" -> financial,
      "maturity" -> bounds.describe(financial.yearsSinceFirstFunding),
      "sentiment" -> sentimentFeatures,
      "headlines" -> headlines.map(h => sentiment(h.text) + ("text" -> h.text) + ("date" -> h.date))
    )
  }

  /** A profile's known-at-cutoff facts as the same FinancialFeatures training used. */
  def financialFeatures(founded: LocalDate, firstFunding: LocalDate, categories: List[String], country: Option[String],
      cutoff: LocalDate): com.startupsurvival.models.FinancialFeatures =
    FinancialFeatureExtractor.extractFinancialFeatures(StartupRecord(
      permalink = "/demo", name = "demo", homepageUrl = None,
      categoryList = Some(categories.mkString("|")).filter(_.nonEmpty), countryCode = country.map(_.trim).filter(_.nonEmpty),
      stateCode = None, region = None, city = None, fundingTotalUsd = None, fundingRounds = None,
      foundedAt = founded, firstFundingAt = firstFunding, lastFundingAt = None,
      label = 1, predictionCutoff = cutoff
    ))

  def loadModels(path: String, structuralPath: Option[String] = None): Models = {
    val lr = parse(new String(Files.readAllBytes(Paths.get(path)), UTF_8)).extract[Models]
    lr.checkFeatureOrder()
    val forest = structuralPath.filter(p => Files.exists(Paths.get(p))).map(Forest.load)
    forest.foreach { f =>
      require(f.categories == lr.categories && f.countries == lr.countries && f.features == FeatureSets.safeFeatureNames(lr.vocab).toList,
        "structural model's features do not match the leak-free feature set")
    }
    lr.copy(structural = forest)
  }

  private def send(ex: HttpExchange, status: Int, contentType: String, body: Array[Byte]): Unit = {
    ex.getResponseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(status, if (body.isEmpty) -1 else body.length)
    if (body.nonEmpty) ex.getResponseBody.write(body)
    ex.close()
  }

  private def sendJson(ex: HttpExchange, status: Int, value: AnyRef): Unit =
    send(ex, status, "application/json", write(value).getBytes(UTF_8))

  /** The last X-Forwarded-For hop is the one Render's proxy appended; earlier hops are client-supplied. */
  private def client(ex: HttpExchange): String =
    Option(ex.getRequestHeaders.getFirst("X-Forwarded-For")).map(_.split(',').last.trim)
      .getOrElse(ex.getRemoteAddress.getAddress.getHostAddress)

  /** JSON endpoint for one method; bad input becomes a 400 with a message. */
  private def api(server: HttpServer, path: String, method: String = "POST")(handle: (HttpExchange, String) => AnyRef): Unit =
    server.createContext(path, (ex: HttpExchange) =>
      try {
        if (ex.getRequestMethod != method) sendJson(ex, 405, Map("error" -> s"$method only"))
        else if (method == "GET") sendJson(ex, 200, handle(ex, ""))
        else {
          // Bounded read (Java 8 has no readNBytes); stops once the limit is passed.
          val body = new java.io.ByteArrayOutputStream()
          val chunk = new Array[Byte](8192)
          Iterator.continually(ex.getRequestBody.read(chunk))
            .takeWhile(n => n != -1 && body.size <= MaxBodyBytes)
            .foreach(body.write(chunk, 0, _))
          if (body.size > MaxBodyBytes) sendJson(ex, 413, Map("error" -> "request too large"))
          else sendJson(ex, 200, handle(ex, body.toString(UTF_8.name)))
        }
      } catch {
        case e @ (_: IllegalArgumentException | _: org.json4s.MappingException | _: com.fasterxml.jackson.core.JsonProcessingException) =>
          sendJson(ex, 400, Map("error" -> Option(e.getMessage).getOrElse("bad request").takeWhile(_ != '\n')))
        case e: Exception =>
          e.printStackTrace()
          sendJson(ex, 500, Map("error" -> "internal error"))
      })

  private val ContentTypes = Map(
    "html" -> "text/html; charset=utf-8", "css" -> "text/css", "js" -> "text/javascript",
    "json" -> "application/json", "svg" -> "image/svg+xml", "png" -> "image/png",
    "ico" -> "image/x-icon", "woff2" -> "font/woff2", "txt" -> "text/plain"
  )

  private def serveStatic(root: Path)(ex: HttpExchange): Unit = {
    val requested = root.resolve(ex.getRequestURI.getPath.stripPrefix("/")).normalize()
    val file = if (Files.isDirectory(requested)) requested.resolve("index.html") else requested
    // normalize() + startsWith blocks ../ traversal out of the site root.
    if (!file.startsWith(root) || !Files.isRegularFile(file)) send(ex, 404, "text/plain", "not found".getBytes(UTF_8))
    else {
      val ext = file.getFileName.toString.split('.').last
      send(ex, 200, ContentTypes.getOrElse(ext, "application/octet-stream"), Files.readAllBytes(file))
    }
  }

  def main(args: Array[String]): Unit = {
    val port = sys.env.getOrElse("PORT", "8080").toInt
    val siteRoot = Paths.get(sys.env.getOrElse("SITE_DIR", "../web/dist")).toAbsolutePath.normalize()
    val modelsPath = sys.env.getOrElse("MODEL_COEFFICIENTS", "../results/model_coefficients_leakfree.json")
    val models = loadModels(modelsPath, Some(sys.env.getOrElse("STRUCTURAL_MODEL", "../results/model_structural.json")))
    println(s"Structural model: ${models.structuralName}, threshold ${models.threshold}")
    // Warm up before taking traffic: on a 0.1-CPU host the first cold request otherwise outlasts the 10s timeout.
    write(predict(PredictRequest("2020-01-01", "2020-06-01", Some(List("Software")), Some("USA"),
      Some(List(Headline("2020-07-01", "Acme raises funding")))), models))
    NameMatcher.computeMatchConfidence("Acme Labs", "Acme Labs raises funding")

    // Slow clients are cut off after 10s and can't starve the pool. Set before the server class loads.
    System.setProperty("sun.net.httpserver.maxReqTime", "10")
    System.setProperty("sun.net.httpserver.maxRspTime", "10")
    // Loopback unless HOST says otherwise (e.g. HOST=0.0.0.0 when deploying).
    val host = sys.env.getOrElse("HOST", "127.0.0.1")
    val server = HttpServer.create(new InetSocketAddress(host, port), 0)
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8))
    val bounds = Maturity.load(sys.env.getOrElse("FEATURE_RANGES", "../results/feature_ranges.json"))
    val store = sys.env.get("KV_URL").filter(_.nonEmpty).map(new Store(_))
    val watch = new Tracker.Watch(models, Tracker.loadProfiles(sys.env.getOrElse("WATCHLIST", "../startups/watchlist.json")), bounds, store,
      new NewsArchive(store))
    api(server, "/api/predict")((_, body) => predict(parse(body).extract[PredictRequest], models, LocalDate.now(), bounds))
    api(server, "/api/sentiment")((_, body) => sentiment(parse(body).extract[SentimentRequest].headline))
    api(server, "/api/watchlist", "GET")((_, _) => watch.snapshot)
    api(server, "/api/track")((ex, body) => watch.suggest(parse(body).extract[Tracker.Profile], client(ex)))
    api(server, "/api/match") { (_, body) =>
      val req = parse(body).extract[MatchRequest]
      NameMatcher.computeMatchConfidence(req.name, req.headline)
    }
    server.createContext("/", (ex: HttpExchange) => serveStatic(siteRoot)(ex))
    server.start()
    watch.start()
    println(s"Serving $siteRoot and /api on http://$host:$port")
  }
}
