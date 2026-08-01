(* Enumerate Isabelle's method + attribute name-space tables from the loaded
   logic image and print them one per line, prefixed by kind.  This is the
   authoritative table Isabelle assembles at runtime — every method/attribute
   is present regardless of the registration form used to declare it (direct
   `Method.setup`, `method_setup`, or a factory like `Induct.gen_induct_setup`),
   which is exactly what a static source scan cannot guarantee.

   Run against the base heap (no AFP, no corpus):
       isabelle ML_process -l HOL -r -f scripts/dump_namespace.ML
   The `xstring` externed names are the user-facing short names as written in
   proofs (`simp`, `induction`, `OF`).  Consumed by scripts/dump_isabelle_tables.py,
   which filters on the DUMP\t prefix so any process banner is ignored. *)

val _ =
  let
    (* Scope the namespace to the UNION of every theory loaded in the heap, not
       one terminal theory: a session registers methods across sibling theories
       (HOL-Eisbach's `match`/`solves`/`determ` live in the `Eisbach` theory,
       not the heap's terminal `Complex_Main`; HOL-Library spreads methods over
       many independent theories), so any single `List.last` theory silently
       drops the rest.  We enumerate each loaded theory's method/attribute space
       and let the consumer deduplicate — the union is the session's *complete*
       table.  QUERY_DUMP_THEORY still pins a single theory when a caller wants
       one. *)
    val names =
      case OS.Process.getEnv "QUERY_DUMP_THEORY" of
        SOME s => [s]
      | NONE => Thy_Info.get_names ();
    val _ = writeln ("DUMP\tTHEORY\t" ^
      (case OS.Process.getEnv "QUERY_DUMP_THEORY" of
         SOME s => s
       | NONE => "merged:" ^ Int.toString (length names)));
    (* `method_space` / `attribute_space` are the public accessors (the tables
       themselves are private); `get_names` lists every declared full name,
       `is_concealed` drops internal helper bindings, `extern` yields the short
       user-facing name as written in proofs. *)
    fun dump_thy nm =
      let
        val ctxt = Proof_Context.init_global (Thy_Info.get_theory nm);
        val generic = Context.Proof ctxt;
        fun dump kind space =
          List.app
            (fn name =>
               if Name_Space.is_concealed space name then ()
               else writeln ("DUMP\t" ^ kind ^ "\t" ^
                             Name_Space.extern ctxt space name))
            (Name_Space.get_names space);
      in
        dump "METHOD" (Method.method_space generic);
        dump "ATTRIB" (Attrib.attribute_space generic)
      end;
  in
    List.app dump_thy names
  end;
