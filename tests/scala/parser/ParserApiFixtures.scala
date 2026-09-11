package isabelle.query.regression
private[regression] object ParserApiFixtures {
val ENTRY_SPAN_FIELDS = List("tag", "name", "theory", "thy_line", "decl_end_line", "proof_line", "body_end_line", "thy_end", "preamble")
val ENTRY_SPAN_PROPERTIES = List("src_start", "line_count")
val SECTION_SPAN_FIELDS = List("theory", "path", "entries", "thy_lines", "outline", "text_blocks", "heading_spans", "comment_ranges", "nonisar_ranges", "nonisar_spans", "inner_spans")
val SECTION_VIEWS = List("source", "slice", "live_source", "outer_source")
val FIXTURE = "theory Doc\nimports Main\nbegin\n\ntext \\<open>A preamble that documents the lemma below.\\<close>\n\nlemma documented:\n  assumes \"P\"\n  shows \"P\"\nproof -\n  show \"P\" by (rule assms)\nqed\n\nend\n"
val DECLARER = "theory A\n  imports Main\n  keywords \"mydef\" :: thy_defn\nbegin\nend\n"
val USER = "theory B\nimports A\nbegin\nmydef gadget :: \"bool\" where \"gadget = True\"\nend\n"
val LIVE_FIXTURE = "theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n"
}
