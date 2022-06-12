package org.polystat.cli

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.syntax.*
import org.polystat.cli.util.InputUtils.*
import org.polystat.odin.analysis.ASTAnalyzer
import org.polystat.odin.analysis.EOOdinAnalyzer
import org.polystat.odin.analysis.EOOdinAnalyzer.OdinAnalysisResult
import org.polystat.odin.parser.EoParser.sourceCodeEoParser
import org.polystat.sarif.AggregatedSarifOutput
import org.polystat.sarif.SarifOutput

import PolystatConfig.*

object EO:

  def analyze(cfg: ProcessedConfig): IO[Unit] =
    def runAnalyzers(
        inputFiles: Vector[(Path, String)]
    ): IO[Vector[(Path, List[OdinAnalysisResult])]] =
      inputFiles
        .traverse { case (codePath, code) =>
          for
            _ <- IO.println(s"Analyzing $codePath...")
            analyzed <- cfg.filteredAnalyzers.traverse(a =>
              a.analyze(
                tmpDir = cfg.tempDir,
                // TODO: make sure cfg input is always converted to directory
                pathToSrcRoot =
                  cfg.input.asInstanceOf[Input.FromDirectory].path,
                pathToCode = codePath,
                code = code,
              ).handleError(e =>
                OdinAnalysisResult.AnalyzerFailure(a.ruleId, e)
              )
            )
          yield (codePath, analyzed)
        }

    def writeToDirs(
        analyzed: Vector[(Path, List[OdinAnalysisResult])]
    ): IO[Unit] =
      analyzed.traverse_ { case (codePath, results) =>
        for
          _ <- if cfg.output.console then IO.println(analyzed) else IO.unit
          _ <- cfg.fmts.traverse_ { case OutputFormat.Sarif =>
            val sarifJson = SarifOutput(
              codePath,
              results,
            ).json.toString
            cfg.output.dirs.traverse_(out =>
              val outPath =
                codePath
                  .mount(
                    to = out / "sarif",
                    relativelyTo =
                      cfg.input.asInstanceOf[Input.FromDirectory].path,
                  )
                  .replaceExt(newExt = ".sarif.json")
              for
                _ <- IO.println(s"Writing results to $outPath...")
                _ <- writeOutputTo(outPath)(sarifJson)
              yield ()
            )
          }
        yield ()
      }

    def writeAggregate(analyzed: Vector[(Path, List[OdinAnalysisResult])]) =
      cfg.output.files.traverse_ { outputPath =>
        cfg.fmts.traverse_ { case OutputFormat.Sarif =>
          for
            _ <- IO.println(s"Writing aggregated output to $outputPath...")
            sariOutput = AggregatedSarifOutput(analyzed).json.toString
            _ <- writeOutputTo(outputPath)(sariOutput)
          yield ()
        }
      }

    for
      inputFiles <- readCodeFromInput(".eo", cfg.input).compile.toVector
      analyzed <- runAnalyzers(inputFiles)
      _ <- cfg.output.dirs.traverse_ { outDir =>
        for
          _ <- IO.println(s"Cleaning $outDir before writing...")
          _ <- outDir.createDirIfDoesntExist.flatMap(_.clean)
        yield ()
      }
      _ <- writeToDirs(analyzed)
      _ <- writeAggregate(analyzed)
    yield ()
    end for
  end analyze
end EO
