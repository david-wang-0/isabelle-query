package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_closed_stdout {
  def run(): Unit = {
    disposition("test_closed_stdout.ALargeAnswerExitsOneFortyOne.test_it_is_deterministic", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ALargeAnswerExitsOneFortyOne.test_status_is_141", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ASmallAnswerExitsZero.test_it_is_deterministic", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ASmallAnswerExitsZero.test_status_is_zero", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ASmallAnswerExitsZero.test_stderr_stays_silent", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.NoShutdownNoise.test_nothing_is_printed_on_stderr", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.NoShutdownNoise.test_the_status_is_never_pythons_default", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ReferenceBehaviour.test_a_large_producer_dies_of_sigpipe", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
    disposition("test_closed_stdout.ReferenceBehaviour.test_a_small_producer_exits_zero", "process", "Real OS pipe timing/SIGPIPE, external seq/head and optional AFP corpus process behavior cannot be established by an in-process writer assertion; retain the original bounded process integration tier.")
  }
}
