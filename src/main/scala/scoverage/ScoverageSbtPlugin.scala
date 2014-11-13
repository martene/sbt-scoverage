package scoverage

import sbt.Keys._
import sbt._
import scoverage.report.{ScoverageHtmlWriter, ScoverageXmlWriter, CoberturaXmlWriter}

object ScoverageSbtPlugin extends ScoverageSbtPlugin

class ScoverageSbtPlugin extends sbt.AutoPlugin {

  val OrgScoverage = "org.scoverage"
  val ScalacRuntimeArtifact = "scalac-scoverage-runtime"
  val ScalacPluginArtifact = "scalac-scoverage-plugin"
  val ScoverageVersion = "1.0.0.BETA1"

  object autoImport {
    lazy val coverage = taskKey[Unit]("enable coverage")
    lazy val coverageReport = taskKey[Unit]("run report generation")
    val coverageExcludedPackages = settingKey[String]("regex for excluded packages")
    val coverageExcludedFiles = settingKey[String]("regex for excluded file paths")
    val coverageMinimumCoverage = settingKey[Double]("scoverage-minimum-coverage")
    val coverageFailOnMinimumCoverage = settingKey[Boolean]("if coverage is less than this value then fail build")
    val coverageHighlighting = settingKey[Boolean]("enables range positioning for highlighting")
    val coverageOutputCobertua = settingKey[Boolean]("enables cobertura XML report generation")
    val coverageOutputXML = settingKey[Boolean]("enables xml report generation")
    val coverageOutputHTML = settingKey[Boolean]("enables html report generation")
    val coverageAggregateReport = settingKey[Boolean]("if true will generate aggregate parent report")
  }

  var enabled = false

  import autoImport._

  override def trigger = allRequirements
  override def projectSettings = Seq(

    coverage := {
      println(enabled)
      enabled = true
      println(enabled)
    },

    coverageReport := {

      s.log.info(s"[info] Waiting for measurement data to sync...")
      Thread.sleep(1000) // have noticed some delay in writing on windows, hacky but works

      report((crossTarget in Test).value,
        (baseDirectory in Compile).value,
        (scalaSource in Compile).value,
        (streams in Global).value,
        coverageMinimumCoverage.value,
        coverageFailOnMinimumCoverage.value)
    },

    libraryDependencies ++= Seq(
      OrgScoverage % (ScalacRuntimeArtifact + "_" + scalaBinaryVersion.value) % ScoverageVersion % "provided",
      OrgScoverage % (ScalacPluginArtifact + "_" + scalaBinaryVersion.value) % ScoverageVersion % "provided"
    ),

    coverageExcludedPackages := "",
    coverageExcludedFiles := "",
    coverageMinimumCoverage := 0, // default is no minimum
    coverageFailOnMinimumCoverage := false,
    coverageHighlighting := true,
    coverageOutputXML := true,
    coverageOutputHTML := true,
    coverageOutputCobertua := true,
    coverageAggregateReport := true,

    scalacOptions in(Compile, compile) ++= {
      val scoverageDeps: Seq[File] = update.value matching configurationFilter("provided")
      scoverageDeps.find(_.getAbsolutePath.contains(ScalacPluginArtifact)) match {
        case None => throw new Exception(s"Fatal: $ScalacPluginArtifact not in libraryDependencies")
        case Some(classpath) =>
          scalaArgs(classpath, crossTarget.value, coverageExcludedPackages.value, coverageExcludedFiles.value)
      }
    },

    // rangepos is broken in some releases of scala so option to turn it off
    scalacOptions in(Compile, compile) ++= (if (enabled && coverageHighlighting.value) List("-Yrangepos") else Nil),

    // disable parallel execution to work around "classes.bak" bug in SBT
    parallelExecution in Test := false
  )

  private def scalaArgs(pluginClass: File, target: File, excludedPackages: String, excludedFiles: String) = {
    if (enabled) {
      println("[info] Scoverage code coverage is enabled")
      Seq(
        Some(s"-Xplugin:${pluginClass.getAbsolutePath}"),
        Some(s"-P:scoverage:dataDir:${target.getAbsolutePath}/scoverage-data"),
        Option(excludedPackages.trim).filter(_.nonEmpty).map(v => s"-P:scoverage:excludedPackages:$v"),
        Option(excludedFiles.trim).filter(_.nonEmpty).map(v => s"-P:scoverage:excludedFiles:$v")
      ).flatten
    } else {
      Nil
    }
  }

  private def report(crossTarget: File,
                     baseDirectory: File,
                     compileSourceDirectory: File,
                     s: TaskStreams,
                     min: Double,
                     failOnMin: Boolean): Unit = {

    val dataDir = crossTarget / "/scoverage-data"
    val reportDir = crossTarget / "scoverage-report"
    val coberturaDir = crossTarget / "coverage-report"
    coberturaDir.mkdirs()
    reportDir.mkdirs()

    val coverageFile = IOUtils.coverageFile(dataDir)
    val measurementFiles = IOUtils.findMeasurementFiles(dataDir)

    s.log.info(s"[[info] ] Reading scoverage instrumentation [$coverageFile]")

    if (coverageFile.exists) {

      s.log.info(s"[[info] ] Reading scoverage measurements...")
      val coverage = IOUtils.deserialize(coverageFile)
      val measurements = IOUtils.invoked(measurementFiles)
      coverage.apply(measurements)

      s.log.info(s"[info] Generating Cobertura report [${coberturaDir.getAbsolutePath}/cobertura.xml]")
      new CoberturaXmlWriter(baseDirectory, coberturaDir).write(coverage)

      s.log.info(s"[info] Generating XML coverage report [${reportDir.getAbsolutePath}/scoverage.xml]")
      new ScoverageXmlWriter(compileSourceDirectory, reportDir, false).write(coverage)
      new ScoverageXmlWriter(compileSourceDirectory, reportDir, true).write(coverage)

      s.log.info(s"[info] Generating HTML coverage report [${reportDir.getAbsolutePath}/index.html]")
      new ScoverageHtmlWriter(compileSourceDirectory, reportDir).write(coverage)

      s.log.info("[info] Coverage reports completed")

      val cper = coverage.statementCoveragePercent
      val cfmt = coverage.statementCoverageFormatted


      // check for default minimum
      if (min > 0) {
        def is100(d: Double) = Math.abs(100 - d) <= 0.00001

        if (is100(min) && is100(cper)) {
          s.log.info(s"[info] 100% Coverage !")
        } else if (min > cper) {
          s.log.error(s"[info] Coverage is below minimum [$coverage.statementCoverageFormatted}% < $min%]")
          if (failOnMin)
            throw new RuntimeException("Coverage minimum was not reached")
        } else {
          s.log.info(s"[info] Coverage is above minimum [$cfmt% > $min%]")
        }
      }

      s.log.info(s"[info] All done. Coverage was [$cfmt%]")
    } else {
      s.log.info(s"[info] Scoverage data file does not exist. Skipping report generation")
    }
  }
}