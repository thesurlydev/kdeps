#!/usr/bin/env bash

set -e

ROOT=$(pwd)
SRC_DIR="${ROOT}/src"
BIN_DIR="${ROOT}/bin"
DIST_DIR="${ROOT}/dist"
STAGING_DIR="${ROOT}/staging"
APP_NAME="kdeps"
APP_MAIN="kdeps.MainKt"
APP_JAR="${APP_NAME}-slim.jar"
APP_FAT_JAR="${APP_NAME}.jar"
CUSTOM_JRE="customjre"
JAVA_VER="21"

function clean() {
  echo "cleaning"
  sudo rm -rf $BIN_DIR
  rm -rf $STAGING_DIR
  rm -rf $CUSTOM_JRE
  rm -rf $DIST_DIR
  rm -f *.jar
  rm -f *.deb
}

function prepare() {
  echo "preparing"
  mkdir -p $STAGING_DIR
  mkdir -p $BIN_DIR
  mkdir -p $DIST_DIR
}

function compile() {
  echo "compiling"
  kotlinc $SRC_DIR -include-runtime -d $STAGING_DIR/$APP_JAR -jvm-target ${JAVA_VER} -no-reflect
}

function create_jar() {
  echo "creating fat jar"
  pushd $STAGING_DIR
    jar xf $APP_JAR
    jar cfe $APP_FAT_JAR $APP_MAIN .
  popd
}

function deps() {
  echo "checking dependencies of $STAGING_DIR/$APP_JAR"
  jdeps --print-module-deps "$STAGING_DIR/$APP_JAR"
}

function create_jre() {
  local jmods="${JAVA_HOME}/jmods"
  local add_modules="java.base"
  echo "creating custom jre from module path: ${jmods}; add_modules: ${add_modules}"
  jlink --module-path "${jmods}" --add-modules ${add_modules} --no-header-files --no-man-pages --strip-debug --output ${CUSTOM_JRE}
}

function package() {
  echo "creating native package"
  jpackage --dest $DIST_DIR --input $STAGING_DIR --name ${APP_NAME} --main-jar $APP_JAR --main-class $APP_MAIN  --runtime-image ${CUSTOM_JRE} --type deb
}

clean
prepare
compile
create_jar
deps
create_jre
package

echo
echo "done"
echo
