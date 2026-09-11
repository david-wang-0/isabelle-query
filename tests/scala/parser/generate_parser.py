"""Offline maintenance translator for the frozen Scala parser regression cases.

Run from any directory: python3 tests/scala/parser/generate_parser.py [--write].
Without --write this checks reproducibility and manifest IDs. It reads only the
original Python SOURCE AST; it never imports or executes those tests or their
parser. Expected literals and predicates therefore come from source, not from
observed Scala output. Unsupported syntax aborts generation rather than skipping
an assertion. Handwritten API/corpus/special cases remain separate.

ParserValues is the data adapter; ParserBridge dispatches actuals to production.
This tool is not imported or invoked by the routine Scala runner. When upstream
tests change, review both generated diffs and handwritten equivalents before
updating their coverage manifest. Class setup is copied into each independent
case and helper assertions/loops are retained. The two file-fixture helpers and
two stdout-capture helpers have explicit API adapters to avoid process globals.
"""
import ast, json, pathlib, argparse, os, difflib, hashlib
options = argparse.ArgumentParser(description='Check or regenerate frozen parser assertions from original tests; never used by the Scala runner.')
options.add_argument('--write', action='store_true', help='replace generated Scala files after checking all modules')
args = options.parse_args()
repo = pathlib.Path(__file__).resolve().parents[3]
os.chdir(repo)
BASE = pathlib.Path('tests/scala/parser')
ledger = json.loads(pathlib.Path('tests/scala/coverage/parser.json').read_text())['cases']
for record in json.loads((BASE / 'source-audit.json').read_text()):
    source = pathlib.Path('tests') / (record['module'] + '.py')
    if hashlib.sha256(source.read_bytes()).hexdigest() != record['source_sha256']:
        raise SystemExit('original source changed; review assertion and loop audit before regeneration: ' + str(source))

mods = sorted({case['id'].split('.')[0] for case in ledger})
case_meta = {case['id']: case for case in ledger}

def q(s):
    return json.dumps(s, ensure_ascii=False)

class Gen:

    def __init__(self, m):
        self.m = m
        self.t = ast.parse(open('tests/' + m + '.py').read())
        self.cls = ''
        self.ctx = 'e'
        self.n = 0
        self.funcs = {n.name: n for n in self.t.body if isinstance(n, ast.FunctionDef)}
        self.classes = {n.name: n for n in self.t.body if isinstance(n, ast.ClassDef)}
        self.helpers = {}
        for c in self.classes.values():
            for f in c.body:
                if isinstance(f, ast.FunctionDef) and (not f.name.startswith('test_')):
                    self.helpers[c.name + '.' + f.name] = f
        self.alias = {}
        for n in self.t.body:
            if isinstance(n, ast.Assign) and isinstance(n.value, ast.Attribute):
                self.alias[ast.unparse(n.targets[0])] = ast.unparse(n.value)

    def key(self, n):
        if isinstance(n, ast.Name):
            return n.id
        if isinstance(n, ast.Attribute) and isinstance(n.value, ast.Name) and (n.value.id == 'self'):
            return n.attr
        raise Exception('assignment target ' + ast.unparse(n))

    def expr(self, n):
        E = self.expr
        if isinstance(n, ast.Constant):
            if n.value is None:
                return 'V.none'
            if isinstance(n.value, bool):
                return 'V(' + str(n.value).lower() + ')'
            return 'V(' + q(n.value) + ')'
        if isinstance(n, ast.Name):
            return self.ctx + '(' + q(n.id) + ')'
        if isinstance(n, ast.Attribute):
            if isinstance(n.value, ast.Name) and n.value.id == 'self':
                return self.ctx + '(' + q(n.attr) + ')'
            if isinstance(n.value, ast.Name) and n.value.id in self.classes:
                return self.ctx + '(' + q(n.value.id + '.' + n.attr) + ')'
            if isinstance(n.value, ast.Name) and n.value.id in ('cli', 'parsing', 'model', 'commands', 'isa_ns'):
                return 'ParserBridge.constant(' + q(ast.unparse(n)) + ')'
            return E(n.value) + '.field(' + q(n.attr) + ')'
        if isinstance(n, (ast.List, ast.Tuple, ast.Set)):
            return ('vs' if not isinstance(n, ast.Set) else 'vset') + '(' + ', '.join((E(x) for x in n.elts)) + ')'
        if isinstance(n, ast.Dict):
            return 'vm(' + ', '.join(('(' + E(k) + ', ' + E(v) + ')' for k, v in zip(n.keys, n.values))) + ')'
        if isinstance(n, ast.Subscript):
            if isinstance(n.slice, ast.Slice):
                s = n.slice
                assert s.step is None
                return E(n.value) + '.slice(' + ', '.join((E(x) if x else 'V.none' for x in [s.lower, s.upper])) + ')'
            return E(n.value) + '.at(' + E(n.slice) + ')'
        if isinstance(n, ast.BinOp):
            return E(n.left) + '.' + {ast.Add: 'plus', ast.Sub: 'minus', ast.Mult: 'times', ast.Div: 'div', ast.BitOr: 'union'}[type(n.op)] + '(' + E(n.right) + ')'
        if isinstance(n, ast.UnaryOp):
            return 'V(!' + E(n.operand) + '.truth)' if isinstance(n.op, ast.Not) else 'V(-' + E(n.operand) + '.int)'
        if isinstance(n, ast.BoolOp):
            return 'V(' + (' && ' if isinstance(n.op, ast.And) else ' || ').join(('(' + E(v) + '.truth)' for v in n.values)) + ')'
        if isinstance(n, ast.Compare):
            pairs = []
            left = n.left
            for op, right in zip(n.ops, n.comparators):
                a, b = (E(left), E(right))
                if isinstance(op, (ast.In, ast.NotIn)):
                    s = b + '.has(' + a + ')'
                    s = '!' + s if isinstance(op, ast.NotIn) else s
                else:
                    s = a + '.cmp(' + q(type(op).__name__) + ', ' + b + ')'
                pairs.append('(' + s + ')')
                left = right
            return 'V(' + ' && '.join(pairs) + ')'
        if isinstance(n, ast.IfExp):
            return '(if (' + E(n.test) + '.truth) ' + E(n.body) + ' else ' + E(n.orelse) + ')'
        if isinstance(n, ast.JoinedStr):
            return 'V(' + ' + '.join((q(x.value) if isinstance(x, ast.Constant) else E(x.value) + '.str' for x in n.values)) + ')'
        if isinstance(n, (ast.ListComp, ast.SetComp, ast.GeneratorExp, ast.DictComp)):
            old = self.ctx

            def comp(gs):
                if not gs:
                    return 'Vector((' + E(n.key) + ', ' + E(n.value) + '))' if isinstance(n, ast.DictComp) else 'Vector(' + E(n.elt) + ')'
                g = gs[0]
                it = E(g.iter)
                self.n += 1
                v = 'c' + str(self.n)
                prev = self.ctx
                self.ctx = v
                bind = self.bind(g.target, 'item', declare=False)
                guard = ' && '.join((E(x) + '.truth' for x in g.ifs)) or 'true'
                inner = comp(gs[1:])
                self.ctx = prev
                return it + '.seq.flatMap { item => val ' + v + ' = ' + prev + '.clone(); ' + bind + '; if (' + guard + ') ' + inner + ' else Vector.empty }'
            body = comp(n.generators)
            self.ctx = old
            return ('V.dict(' if isinstance(n, ast.DictComp) else 'V.set(' if isinstance(n, ast.SetComp) else 'V.seq(') + body + ')'
        if isinstance(n, ast.Call):
            args = [E(a) for a in n.args if not isinstance(a, ast.Starred)]
            star = [E(a.value) for a in n.args if isinstance(a, ast.Starred)]
            kwargs = 'Map(' + ', '.join((q(k.arg) + ' -> ' + E(k.value) for k in n.keywords)) + ')'
            fn = ast.unparse(n.func)
            if isinstance(n.func, ast.Name) and fn in self.funcs:
                return 'h_' + fn + '(' + self.ctx + ', Vector(' + ', '.join(args) + '), ' + kwargs + ')'
            if fn.startswith('self.') and self.cls + '.' + fn[5:] in self.helpers:
                return 'h_' + self.cls + '_' + fn[5:] + '(' + self.ctx + ', Vector(' + ', '.join(args) + '), ' + kwargs + ')'
            if isinstance(n.func, ast.Name):
                fn = self.alias.get(fn, fn)
            if isinstance(n.func, ast.Attribute) and (not (isinstance(n.func.value, ast.Name) and n.func.value.id in ('cli', 'parsing', 'model', 'graph', 'commands', 'shape', 're', 'api'))):
                return E(n.func.value) + '.invoke(' + q(n.func.attr) + ', Vector(' + ', '.join(args) + '), ' + kwargs + ')'
            return 'ParserBridge.call(' + q(fn) + ', ' + ('Vector(' + ', '.join(args) + ')' + ''.join((' ++ ' + x + '.seq' for x in star))) + ', ' + kwargs + ')'
        raise Exception('expr ' + ast.dump(n))

    def bind(self, target, value, declare=False):
        if isinstance(target, (ast.Tuple, ast.List)):
            self.n += 1
            v = 'unpack' + str(self.n)
            return 'val ' + v + ' = ' + value + '; TestSupport.equal(' + v + '.seq.size, ' + str(len(target.elts)) + ', "unpack arity"); ' + '; '.join((self.bind(t, v + '.at(V(' + str(i) + '))') for i, t in enumerate(target.elts)))
        return self.ctx + '(' + q(self.key(target)) + ') = ' + value

    def stmt(self, n):
        E = self.expr
        if isinstance(n, ast.Expr):
            if isinstance(n.value, ast.Constant) and isinstance(n.value.value, str):
                return ''
            if isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Attribute) and (ast.unparse(n.value.func.value) == 'self') and n.value.func.attr.startswith('assert') and (self.cls + '.' + n.value.func.attr not in self.helpers):
                call = n.value
                name = call.func.attr
                a = [E(x) for x in call.args]
                clue = a[2] + '.str' if len(a) > 2 else q('tests/' + self.m + '.py:' + str(n.lineno))
                if name == 'assertEqual':
                    return 'TestSupport.equal(' + a[0] + ', ' + a[1] + ', ' + clue + ')'
                if name == 'assertNotEqual':
                    return 'TestSupport.check(!' + a[0] + '.cmp("Eq", ' + a[1] + '), ' + clue + ')'
                if name in ['assertIn', 'assertNotIn']:
                    return 'TestSupport.check(' + ('!' if name == 'assertNotIn' else '') + a[1] + '.has(' + a[0] + '), ' + clue + ' + " actual=" + ' + a[1] + ')'
                if name in ['assertLess', 'assertLessEqual', 'assertGreater', 'assertGreaterEqual']:
                    op = {'assertLess': 'Lt', 'assertLessEqual': 'LtE', 'assertGreater': 'Gt', 'assertGreaterEqual': 'GtE'}[name]
                    return 'TestSupport.check(' + a[0] + '.cmp(' + q(op) + ', ' + a[1] + '), ' + clue + ' + " actual=" + ' + a[0] + ' + " expected=" + ' + a[1] + ')'
                if name in ['assertTrue', 'assertFalse']:
                    return 'TestSupport.check(' + ('!' if name == 'assertFalse' else '') + a[0] + '.truth, ' + clue + ')'
                if name in ['assertIsNone', 'assertIsNotNone']:
                    return 'TestSupport.check(' + ('!' if name == 'assertIsNotNone' else '') + a[0] + '.isNone, ' + clue + ')'
                if name == 'assertIs':
                    return 'TestSupport.check(' + a[0] + '.same(' + a[1] + '), ' + clue + ')'
                raise Exception('assert ' + name)
            return E(n.value)
        if isinstance(n, ast.Assign):
            return '; '.join((self.bind(t, E(n.value)) for t in n.targets))
        if isinstance(n, ast.AugAssign):
            return self.bind(n.target, E(ast.BinOp(left=n.target, op=n.op, right=n.value)))
        if isinstance(n, ast.Return):
            return 'return ' + (E(n.value) if n.value else 'V.none')
        if isinstance(n, ast.Assert):
            return 'TestSupport.check(' + E(n.test) + '.truth, ' + (E(n.msg) + '.str' if n.msg else q('assert')) + ')'
        if isinstance(n, ast.For):
            return E(n.iter) + '.seq.foreach { item => ' + self.bind(n.target, 'item') + ';\n' + self.body(n.body) + '\n}'
        if isinstance(n, ast.If):
            return 'if (' + E(n.test) + '.truth) {\n' + self.body(n.body) + '\n}' + (' else {\n' + self.body(n.orelse) + '\n}' if n.orelse else '')
        if isinstance(n, ast.With):
            if all((ast.unparse(x.context_expr.func) == 'self.subTest' for x in n.items)):
                return 'TestSupport.subcase(' + q('tests/' + self.m + '.py:' + str(n.lineno)) + ' + ' + self.expr(ast.Dict(keys=[ast.Constant(k.arg) for item in n.items for k in item.context_expr.keywords], values=[k.value for item in n.items for k in item.context_expr.keywords])) + '.str) {\n' + self.body(n.body) + '\n}'
            raise Exception('with ' + ast.unparse(n))
        if isinstance(n, ast.Pass):
            return ''
        if isinstance(n, (ast.Import, ast.ImportFrom)):
            return ''
        raise Exception('stmt ' + ast.dump(n))

    def body(self, ns):
        return '\n'.join(filter(None, (self.stmt(n) for n in ns)))

    def helper(self, name, f, cls=''):
        self.cls = cls
        out = ['private def h_' + name + '(parent: Env, args: Vector[V], kw: Map[String,V]): V = {', 'val e = parent.clone()']
        args = f.args.args
        if cls:
            args = args[1:]
        defaults = [None] * (len(args) - len(f.args.defaults)) + f.args.defaults
        for i, (arg, d) in enumerate(zip(args, defaults)):
            out.append('e(' + q(arg.arg) + ') = kw.getOrElse(' + q(arg.arg) + ', args.lift(' + str(i) + ').getOrElse(' + (self.expr(d) if d else 'throw new IllegalArgumentException("missing ' + arg.arg + '")') + '))')
        if f.args.vararg:
            out.append('e(' + q(f.args.vararg.arg) + ') = V.seq(args.drop(' + str(len(args)) + '))')
        override = None
        if self.m == 'test_comment_before_name' and name == 'parse':
            override = 'return V(ParserBridge.section(e("HEAD").str + e("body").str + e("TAIL").str, "Probe"))'
        if self.m == 'test_decl_body_comment' and name in ('entry', 'entries'):
            override = 'return V(ParserBridge.section(e("HEAD").str + e("body").str + e("TAIL").str, "Probe").entries' + ('.head' if name == 'entry' else '') + ')'
        if self.m == 'test_src_doc_attribution' and name == 'EndToEndEnclosing__run':
            override = 'return V(ParserBridge.enclosing(List(h__sec(e, Vector.empty, Map.empty).section),List(e("locus").str)))'
        if self.m == 'test_targets' and name == 'Rendering__enclosing':
            override = 'return V(ParserBridge.enclosing(List(e("sec").section),List(e("locus").str))._1)'
        out.append(override if override else self.body(f.body))
        out += ['V.none', '}']
        return '\n'.join(out)

    def generate(self):
        out = ['package isabelle.query.regression', 'import ParserValues.*', '/** Assertion-for-assertion port of tests/' + self.m + '.py. Fixture literals retain source bytes. */', 'private[regression] object Parser_' + self.m + ' {']
        for name, f in self.funcs.items():
            out.append(self.helper(name, f))
        for name, f in self.helpers.items():
            if name.endswith(('.setUp', '.tearDown', '.setUpClass', '.tearDownClass')):
                continue
            out.append(self.helper(name.replace('.', '_'), f, name.split('.')[0]))
        runs = []
        for cls, c in self.classes.items():
            self.cls = cls
            consts = [n for n in self.t.body if isinstance(n, ast.Assign) and (not isinstance(n.value, ast.Attribute))]
            pre = []
            for other in self.classes.values():
                for n in other.body:
                    if isinstance(n, ast.Assign):
                        for t in n.targets:
                            pre.append('e(' + q(other.name + '.' + t.id) + ') = ' + self.expr(n.value))
            local = [n for n in c.body if isinstance(n, ast.Assign)]
            setup = next((n for n in c.body if isinstance(n, ast.FunctionDef) and n.name == 'setUp'), None)
            for f in c.body:
                if not isinstance(f, ast.FunctionDef) or not f.name.startswith('test_'):
                    continue
                id = self.m + '.' + cls + '.' + f.name
                if id in SPECIAL:
                    continue
                method = 'case_' + cls + '_' + f.name
                runs.append(method + '()')
                expected_failure = any(ast.unparse(d) == 'unittest.expectedFailure' for d in f.decorator_list)
                if expected_failure:
                    if case_meta[id]['status'] != 'expected-failure':
                        raise Exception('original expectedFailure missing from manifest: ' + id)
                    registration = 'TestSupport.expectedFailure(' + q(id) + ', ' + q(case_meta[id]['reason']) + ')'
                else:
                    registration = 'TestSupport.test(' + q(id) + ')'
                out.append('private def ' + method + '(): Unit = ' + registration + ' {\nval e: Env = scala.collection.mutable.Map.empty')
                out.append(self.body(consts))
                out.extend(pre)
                out.append(self.body(local))
                if setup:
                    out.append(self.body(setup.body))
                out.append(self.body(f.body))
                if id == 'test_live_source.WhatIsBlanked.test_legacy_verbatim_goes':
                    out.append('ParserLegacy.inlineGuards()')
                if id == 'test_nonisar_regions.NestedAndInline.test_legacy_verbatim_is_not_live':
                    out.append('ParserLegacy.regionGuards()')
                out.append('}')
                COVER.append({'id': id, 'status': case_meta[id]['status'], 'destination': str(BASE / ('Parser_' + self.m + '.scala')), 'reason': case_meta[id]['reason']})
        out += ['def run(): Unit = { ' + '; '.join(runs) + ' }', '}']
        return '\n'.join(out) + '\n'
SPECIAL = {'test_balanced.BalancedEnd.test_quote_aware_skips_a_quoted_paren', 'test_live_source.Shape.test_result_is_cached', 'test_live_source.Shape.test_clean_theory_shares_the_source_list'}
COVER = []
fails = []
generated = {}
for m in mods:
    if m in ('test_api_surface', 'test_corpus'):
        continue
    try:
        before = len(COVER)
        g = Gen(m)
        result = g.generate()
        generated[BASE / ('Parser_' + m + '.scala')] = result
    except Exception as ex:
        COVER = COVER[:before]
        fails.append((m, str(ex)))
print('generated', len(COVER), 'failures', fails)
if fails:
    raise SystemExit(1)
expected = {case['id'] for case in ledger if pathlib.Path(case['destination']).name.startswith('Parser_test_')}
if {case['id'] for case in COVER} != expected:
    raise SystemExit('generated IDs disagree with frozen manifest')
drift = []
for path, text in generated.items():
    if args.write:
        path.write_text(text)
    elif path.read_text() != text:
        drift.append(str(path))
if drift:
    raise SystemExit('generated-source drift: ' + ', '.join(drift))
print('Frozen Scala sources ' + ('updated' if args.write else 'verified'))
