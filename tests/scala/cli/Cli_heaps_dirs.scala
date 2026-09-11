package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_heaps_dirs {
  def run(): Unit = {
    disposition("test_heaps_dirs.DistributionOnlySession.test_absent_session_is_still_absent", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.DistributionOnlySession.test_built_sessions_includes_it", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.DistributionOnlySession.test_fingerprint_is_not_empty", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.DistributionOnlySession.test_heap_file_finds_it", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.SearchPath.test_an_explicit_system_variable_wins", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.SearchPath.test_home_resolves_through_a_symlink", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.SearchPath.test_one_directory_when_both_names_agree", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.SearchPath.test_system_directory_is_derived_from_the_binary", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.SearchPath.test_user_directory_comes_first", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.UserHeapWins.test_a_session_in_both_resolves_to_the_user_copy", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
    disposition("test_heaps_dirs.UserHeapWins.test_built_sessions_does_not_double_count", "python-only", "Python namespace-resolver installation/heap lookup and fingerprint mechanism with patched process environment; Scala uses compiled namespace tables and has no corresponding external-heap lookup API. Retain Python reference coverage.")
  }
}
