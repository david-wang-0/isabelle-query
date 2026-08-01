(* Resolve operator glyphs to their canonical head constant, for the committed
   notation table (scripts/extract_notation.py writes src/isabelle_query/_notation.py).

   Glyphs arrive newline-separated in $QUERY_NOTATION_GLYPHS; each resolves via the
   operator-section parse  "(GLYPH)" -> head Const  (e.g. "(\<in>)" -> Set.member),
   emitted as  "NOTATION<TAB>GLYPH<TAB>CONST"  ("" when it does not resolve).

   There are deliberately NO Isabelle-symbol literals in this source — the glyphs
   come from the environment at run time — so it loads cleanly under `ML_process
   -f`, whose Poly/ML lexer rejects `\<sym>` string escapes. *)
fun getenv k = Option.getOpt (OS.Process.getEnv k, "");
val thy = Thy_Info.get_theory (case getenv "QUERY_NOTATION_THEORY" of "" => "Main" | t => t);
val ctxt = Proof_Context.init_global thy;

fun head_const s =
  (let
     val t = Syntax.read_term ctxt ("(" ^ s ^ ")");
     fun h (Const (c, _)) = c
       | h (t1 $ _) = h t1
       | h (Abs (_, _, b)) = h b
       | h _ = ""
   in h t end) handle _ => "";

val glyphs = String.tokens (fn c => c = #"\n") (getenv "QUERY_NOTATION_GLYPHS");
val () = List.app (fn g => writeln ("NOTATION\t" ^ g ^ "\t" ^ head_const g)) glyphs;
