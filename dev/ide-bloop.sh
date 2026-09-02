#!/usr/bin/env bash
#
# ide-bloop.sh -- write a Bloop build definition (.bloop/*.json) for an IDE.
#
# Metals (VS Code, Neovim, ...) has no idea what `isabelle scala_build` would
# put on the classpath, so with nothing else to go on it falls back to a
# scala-cli "loose files" build and reports every Isabelle symbol as missing.
# This script derives the real thing from the Isabelle settings environment --
# the same Scala 3 the distribution ships, the same jars `isabelle scalac`
# sees, plus the jEdit jars the plugin compiles against -- and writes three
# Bloop projects:
#
#   query_base   the engine, CLI and server      (query_base/src)
#   jedit_query  the jEdit plugin                (jedit_query/src, needs query_base)
#   dev_probes   the offline plugin probes       (dev/p[56]*probe.scala, needs both)
#
# Nothing here is a build: `isabelle scala_build` stays the only way the jars
# get made.  This is a VIEW of that build for a language server, regenerated
# whenever the source list or the Isabelle installation changes.  `.bloop/`
# is gitignored (it holds machine-specific paths); re-run this after moving
# the checkout or switching Isabelle versions.
#
# Usage:  dev/ide-bloop.sh            then "Metals: Connect to build server"
#         dev/ide-bloop.sh --print    show the derived classpath and exit

set -u
repo=$(cd -- "$(dirname -- "$0")/.." && pwd)

getenv() { isabelle getenv -b "$1" 2>/dev/null; }

isabelle_home=$(getenv ISABELLE_HOME)
scala_home=$(getenv SCALA_HOME)
jdk_home=$(getenv ISABELLE_JDK_HOME)
jedit_settings=$(getenv JEDIT_SETTINGS)
if [ -z "$isabelle_home" ] || [ -z "$scala_home" ] || [ -z "$jdk_home" ]; then
  echo "ide-bloop: cannot read the Isabelle settings environment (is 'isabelle' on PATH?)" >&2
  exit 2
fi

scala_version=$(basename "$(ls -d "$scala_home"/lib/scala3-compiler_3-*.jar)" \
  | sed 's/^scala3-compiler_3-//; s/\.jar$//')

# The classpath `isabelle scalac` uses for a component: every registered
# component's module, the distribution's own jars, then the jEdit jars the
# plugin needs.  Our OWN two jars are dropped -- the sources are the project.
classpath_of() {
  printf '%s\n' "$@" | tr ':' '\n' | grep -v '^$' \
    | grep -v '/isabelle_query\.jar$' | grep -v '/isabelle_query_plugin\.jar$'
}
base_cp=$(classpath_of "$(getenv ISABELLE_SETUP_CLASSPATH)" "$(getenv ISABELLE_CLASSPATH)")
jedit_cp=$(classpath_of "$(getenv JEDIT_JARS)")
# The Isabelle/jEdit plugin itself (isabelle.jedit.*) is a dynamic module the
# distribution builds into the user home at jEdit start-up; it is only READ
# here.  Absent until jEdit has run once -- then the plugin project shows
# `isabelle.jedit` as missing, and nothing else is affected.
for j in isabelle_jedit_base isabelle_jedit_main; do
  [ -n "$jedit_settings" ] && [ -f "$jedit_settings/jars/$j.jar" ] && jedit_cp="$jedit_cp
$jedit_settings/jars/$j.jar"
done

if [ "${1:-}" = "--print" ]; then
  echo "scala   $scala_version  ($scala_home)"
  echo "jdk     $jdk_home"
  echo "--- base classpath ---"; echo "$base_cp"
  echo "--- jedit classpath ---"; echo "$jedit_cp"
  exit 0
fi

# Isabelle's scalac options, minus the ones that mean nothing to a language
# server (JVM flags, terminal colour and width).
scalac_opts='"-encoding", "UTF-8", "-feature", "-java-output-version", "21", "-source", "3.3", "-old-syntax", "-no-indent"'

json_list() {            # newline-separated paths -> a JSON array body
  local first=1 line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    [ $first = 1 ] || printf ',\n'
    printf '      "%s"' "$line"
    first=0
  done
}

out="$repo/.bloop"
mkdir -p "$out" || exit 2

write_project() {         # name  sources(newline list)  deps(json)  classpath(newline list)
  local name=$1 sources=$2 deps=$3 cp=$4
  cat >"$out/$name.json" <<EOF
{
  "version": "1.4.0",
  "project": {
    "name": "$name",
    "directory": "$repo",
    "workspaceDir": "$repo",
    "sources": [
$(printf '%s\n' "$sources" | json_list)
    ],
    "dependencies": [$deps],
    "classpath": [
$(printf '%s\n' "$cp" | json_list)
    ],
    "out": "$out/$name",
    "classesDir": "$out/$name/classes",
    "scala": {
      "organization": "org.scala-lang",
      "name": "scala-compiler",
      "version": "$scala_version",
      "options": [$scalac_opts],
      "jars": [
$(ls "$scala_home"/lib/*.jar | json_list)
      ]
    },
    "java": { "options": [] },
    "platform": {
      "name": "jvm",
      "config": { "home": "$jdk_home", "options": [] },
      "mainClass": []
    },
    "tags": ["library"]
  }
}
EOF
}

# Sources are the SAME lists build.props compiles, read from the files, so a
# module added to the build is a module the IDE sees on the next run.
sources_of() {            # component dir -> newline list of absolute sources
  local dir=$1
  sed -n '/^sources *=/,/^[a-z_]* *=/p' "$dir/etc/build.props" \
    | grep -o 'src/[A-Za-z0-9_]*\.scala' | sed "s#^#$dir/#"
}

qb_sources=$(sources_of "$repo/query_base")
jq_sources=$(sources_of "$repo/jedit_query")
probe_sources=$(ls "$repo"/dev/p[0-9]*probe.scala)

write_project query_base "$qb_sources" "" "$base_cp"
write_project jedit_query "$jq_sources" '"query_base"' "$out/query_base/classes
$base_cp
$jedit_cp"
write_project dev_probes "$probe_sources" '"query_base", "jedit_query"' "$out/query_base/classes
$out/jedit_query/classes
$base_cp
$jedit_cp"

echo "wrote $out/{query_base,jedit_query,dev_probes}.json  (scala $scala_version)"
echo "next: in the editor, 'Metals: Connect to build server' (or restart Metals)"
