#!/usr/bin/env bash
# Builds the EL jars replacing Tomcat's lib/el-api.jar and lib/jasper-el.jar into $3.
# $1 = tomcat | expressly, $2 = ref ("<ref>" or "<ref>,<api ref>" for expressly), $3 = output dir.
set -euo pipefail
impl=$1 refs=$2 out=$3
work=$(mktemp -d)
mkdir -p "$out"
if [ "$impl" = tomcat ]; then
    git clone -q --depth 1 --branch "$refs" https://github.com/AngeloRubens/tomcat.git "$work/src"
    cd "$work/src"
    mkdir -p "$work/api" "$work/impl/META-INF"
    # OSGi metadata only (bnd is not on the classpath here); the services file below registers the factory.
    sed -i '/@aQute\.bnd\.annotation/d' java/org/apache/el/ExpressionFactoryImpl.java
    javac --release 17 -nowarn -encoding UTF-8 -d "$work/api" $(find java/jakarta/el -name '*.java')
    javac --release 17 -nowarn -encoding UTF-8 -cp "$work/api" -d "$work/impl" $(find java/org/apache/el -name '*.java')
    (cd java && find jakarta/el -name '*.properties' -exec cp --parents {} "$work/api" \;)
    (cd java && find org/apache/el -name '*.properties' -exec cp --parents {} "$work/impl" \;)
    cp -r res/META-INF/jasper-el.jar/services "$work/impl/META-INF/"
    jar cf "$out/el-api.jar" -C "$work/api" .
    jar cf "$out/jasper-el.jar" -C "$work/impl" .
else
    ref=${refs%%,*} apiref=${refs#*,}
    git clone -q --depth 1 --branch "$apiref" https://github.com/AngeloRubens/expression-language.git "$work/api"
    mvn -B -q -f "$work/api/api/pom.xml" package -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true
    git clone -q --depth 1 --branch "$ref" https://github.com/AngeloRubens/expressly.git "$work/impl"
    mvn -B -q -f "$work/impl/pom.xml" package -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true
    # Same names as Tomcat's jars, so that they replace them.
    cp "$(ls "$work"/api/api/target/jakarta.el-api-*.jar | grep -v -e sources -e javadoc | head -1)" "$out/el-api.jar"
    cp "$(ls "$work"/impl/target/expressly-*.jar | grep -v -e sources -e javadoc | head -1)" "$out/jasper-el.jar"
fi
ls -l "$out"
