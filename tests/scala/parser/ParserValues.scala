package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Path as JPath}
import java.util.regex.{Pattern, Matcher}
import scala.collection.mutable

/** Data-only adapters for the frozen assertion port. No parser lives here.
  * Unknown fields/operations fail loudly; None is produced only by an actual
  * production Option or an explicitly supplied missing-map-key default.
  */
private[regression] object ParserValues {
  type Env = mutable.Map[String, V]
  def unsupported(what: String): Nothing = throw new AssertionError("unsupported parser-port operation: " + what)
  def vs(xs: V*): V = V.seq(xs)
  def vm(xs: (V,V)*): V = V.dict(xs)
  def vset(xs: V*): V = V.set(xs)

  object V {
    val none = new V(null)
    def seq(xs: Iterable[V]): V = new V(xs.toVector)
    def set(xs: Iterable[V]): V = new V(xs.toSet)
    def dict(xs: Iterable[(V,V)]): V = new V(xs.toMap)
    def apply(a: Any): V = a match {
      case v: V => v
      case null | None => none
      case Some(x) => apply(x)
      case m: scala.collection.Map[?,?] => dict(m.iterator.map((k,v) => apply(k) -> apply(v)).toVector)
      case s: Set[?] => set(s.map(apply))
      case s: Iterable[?] => seq(s.map(apply))
      case a: Array[?] => seq(a.toVector.map(apply))
      case p: Product if p.productPrefix.startsWith("Tuple") => seq(p.productIterator.map(apply).toVector)
      case x => new V(x)
    }
  }
  final class V(val raw: Any) {
    def isNone: Boolean = raw == null
    def str: String = raw match {
      case null => "None"
      case s: String => s
      case b: Boolean => if (b) "True" else "False"
      case _ => raw.toString
    }
    def int: Int = raw match {
      case n: Int => n
      case n: Long => n.toInt
      case _ => unsupported("integer conversion of " + raw)
    }
    def seq: Vector[V] = raw match {
      case xs: Vector[?] => xs.asInstanceOf[Vector[V]]
      case xs: Set[?] => xs.toVector.asInstanceOf[Vector[V]]
      case xs: Map[?,?] => xs.keys.toVector.asInstanceOf[Vector[V]]
      case c: ParserBridge.MethodCounts => c.values.keys.toVector.map(V(_))
      case s: String => s.codePoints().toArray.toVector.map(c => V(new String(Character.toChars(c))))
      case _ => unsupported("sequence conversion of " + raw)
    }
    def map: Map[V,V] = raw match {
      case m: Map[?,?] => m.asInstanceOf[Map[V,V]]
      case c: ParserBridge.MethodCounts => c.values.map((k,v) => V(k) -> V(v))
      case _ => unsupported("map conversion of " + raw)
    }
    def strings: List[String] = seq.map(_.str).toList
    def sections: List[Theory_Section] = seq.map(_.section).toList
    def section: Theory_Section = raw match {
      case s: Theory_Section => s
      case _ => unsupported("section conversion " + raw)
    }
    def entry: Entry = raw match {
      case e: Entry => e
      case _ => unsupported("entry conversion " + raw)
    }
    def truth: Boolean = raw match {
      case null => false
      case x: Boolean => x
      case n: Int => n != 0
      case s: String => s.nonEmpty
      case xs: Iterable[?] => xs.nonEmpty
      case _ => true
    }
    def at(k: V): V = raw match {
      case c: ParserBridge.MethodCounts => V(c.values.getOrElse(k.str,0))
      case m: Map[?,?] => map.getOrElse(k, unsupported("missing required map key " + k))
      case _ => val xs = seq; xs(if (k.int < 0) xs.size + k.int else k.int)
    }
    def slice(lo: V, hi: V): V = {
      val xs = seq
      def pos(v: V, d: Int): Int = if (v.isNone) d else if (v.int < 0) xs.size + v.int else v.int
      val result = xs.slice(pos(lo,0),pos(hi,xs.size))
      if (raw.isInstanceOf[String]) V(result.map(_.str).mkString) else V.seq(result)
    }
    def has(x: V): Boolean = raw match {
      case s: String => s.contains(x.str)
      case m: Map[?,?] => map.contains(x)
      case c: ParserBridge.MethodCounts => c.values.contains(x.str)
      case _ => seq.contains(x)
    }
    def plus(x: V): V = (raw,x.raw) match {
      case (a: String,b: String) => V(a+b)
      case (a: Int,b: Int) => V(a+b)
      case (_: Vector[?],_: Vector[?]) => V.seq(seq ++ x.seq)
      case _ => unsupported("+ on " + raw + " and " + x.raw)
    }
    def minus(x: V): V = V(int-x.int)
    def times(x: V): V = raw match {
      case s: String => V(s * x.int)
      case n: Int => V(n*x.int)
      case _ => unsupported("* on " + raw)
    }
    def div(x: V): V = V(int.toDouble / x.int)
    def union(x: V): V = V.set(seq ++ x.seq)
    def same(x: V): Boolean = raw.asInstanceOf[AnyRef] eq x.raw.asInstanceOf[AnyRef]
    private def compare(x: V): Int = (raw,x.raw) match {
      case (a: Int,b: Int) => a.compare(b)
      case (a: String,b: String) => a.compareTo(b)
      case (_: Vector[?],_: Vector[?]) =>
        seq.zip(x.seq).iterator.map((a,b) => a.compare(b)).find(_ != 0).getOrElse(seq.size.compare(x.seq.size))
      case _ => unsupported("ordering " + raw + " and " + x.raw)
    }
    def cmp(op: String, x: V): Boolean = op match {
      case "Eq" => this == x
      case "NotEq" => this != x
      case "Is" => same(x)
      case "IsNot" => !same(x)
      case "LtE" if raw.isInstanceOf[Set[?]] => seq.toSet.subsetOf(x.seq.toSet)
      case "GtE" if raw.isInstanceOf[Set[?]] => x.seq.toSet.subsetOf(seq.toSet)
      case "Lt" => compare(x) < 0
      case "LtE" => compare(x) <= 0
      case "Gt" => compare(x) > 0
      case "GtE" => compare(x) >= 0
      case _ => unsupported("comparison " + op)
    }
    override def equals(other: Any): Boolean = other match { case v: V => raw == v.raw; case _ => false }
    override def hashCode: Int = if (raw == null) 0 else raw.hashCode
    override def toString: String = str

    def field(name: String): V = raw match {
      case e: Entry => V(name match {
        case "name" => e.name
        case "tag" => e.tag
        case "theory" => e.theory
        case "text" => e.text
        case "thy_line" => e.thy_line
        case "thy_end" => e.thy_end
        case "decl_end_line" => e.decl_end_line
        case "proof_line" => e.proof_line
        case "body_end_line" => e.body_end_line
        case "src_start" => e.src_start
        case "line_count" => e.line_count
        case "preamble" => e.preamble
        case "annotations" => e.annotations
        // Python's derived property is exactly the production annotation subset.
        case "roadmap" => e.annotations.collect { case (ln,text,"proof") => (ln,text) }
        case "bindings" => e.bindings
        case "bound_names" => e.bound_names
        case "blocks" => e.blocks
        case "in_target" => e.in_target
        case "target" => e.target
        case _ => unsupported("Entry." + name)
      })
      case s: Theory_Section => V(name match {
        case "entries" => s.entries
        case "path" => s.path
        case "theory" => s.theory
        case "thy_lines" => s.thy_lines
        case "text_blocks" => s.text_blocks
        case "heading_spans" => s.heading_spans
        case "comment_ranges" => s.comment_ranges
        case "nonisar_ranges" => s.nonisar_ranges
        case "nonisar_spans" => ParserBridge.spans(s.regions.nonisar)
        case "inner_spans" => ParserBridge.spans(s.regions.inner)
        case "outline" => s.outline
        case "session" => s.session
        case _ => unsupported("Theory_Section." + name)
      })
      case g: Usage_Graph.Call_Graph => V(name match {
        case "callers" => g.callers.view.mapValues(_.toSet).toMap
        case "callees" => g.callees.view.mapValues(_.toSet).toMap
        case "all_names" => g.all_names
        case _ => unsupported("Call_Graph."+name)
      })
      case p: Shape.Proof_Metrics if name == "steps" => V(p.steps)
      case s: Shape.Step => V(name match {
        case "line" => s.line
        case "kw" => s.kw
        case _ => unsupported("Step."+name)
      })
      case _ => unsupported("field " + name + " on " + raw)
    }
    def invoke(name: String, args: Vector[V], kw: Map[String,V] = Map.empty): V = {
      raw match {
        case s: Theory_Section => return V(name match {
          case "source" => s.source
          case "live_source" => s.live_source
          case "outer_source" => s.outer_source
          case "slice" => s.slice(args(0).int,args(1).int)
          case _ => unsupported("Theory_Section method " + name)
        })
        case p: Pattern => return V(name match {
          case "findall" => Py.find_all(p,args(0).str)
          case "match" => Py.matches_at_start(p,args(0).str)
          case _ => unsupported("Pattern method " + name)
        })
        case m: Matcher if name == "group" => return V(m.group(args(0).int))
        case _ => ()
      }
      name match {
        case "get" => map.getOrElse(args(0),args.lift(1).getOrElse(V.none))
        case "items" => V.seq(map.toVector.map((k,v) => vs(k,v)))
        case "values" => V.seq(map.values)
        case "strip" | "rstrip" =>
          val s = str
          if (args.isEmpty) V(if (name == "strip") Py.strip(s) else Py.rstrip(s))
          else {
            val chars = args(0).str.toSet
            V(if (name == "strip") s.dropWhile(chars).reverse.dropWhile(chars).reverse
              else s.reverse.dropWhile(chars).reverse)
          }
        case "startswith" => V(str.startsWith(args(0).str))
        case "endswith" => V(str.endsWith(args(0).str))
        case "splitlines" => V(Py.split_lines(str)._1)
        case "split" => V(if (args.isEmpty) Py.strip(str).split("\\s+").filter(_.nonEmpty).toVector
          else str.split(Pattern.quote(args(0).str),-1).toVector)
        case "join" => V(args(0).seq.map(_.str).mkString(str))
        case "format" =>
          var result = str
          args.foreach { a => val at=result.indexOf("{}"); if(at < 0) unsupported("format placeholder"); result=result.take(at)+a.str+result.drop(at+2) }
          V(result)
        case "index" => val at=str.indexOf(args(0).str); if(at < 0) throw new IllegalArgumentException("substring not found"); V(at)
        case "count" => V(str.sliding(args(0).str.length).count(_ == args(0).str))
        case "replace" => V(str.replace(args(0).str,args(1).str))
        case _ => unsupported("method " + name + " on " + raw)
      }
    }
  }
}
