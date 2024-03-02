# kdeps

A small CLI to download Maven dependencies from a file.

This project also serves as an example for using `jdeps`, `jlink`, and `jpackage` to create a custom runtime image and
package it as a platform-specific installer.

## Requirements

- JDK 21
- Kotlin

## Build

```shell
./build.sh
```

## Install OS Package

Note: Currently, only `deb` package is supported.

```shell
./install.sh
```

By default, kdeps gets installed in `/opt/kdeps` directory so you may need to add it to your `PATH` environment
variable.


## Usage

### Read dependencies from stdin

```shell
echo "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0" | kdeps
```
will download the dependency and save it to the default output directory `./lib`.

### Read dependencies from a file

As an example, create a file `dependencies.txt` with the following content:

```text
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3
```

```shell
kdeps -f dependencies.txt
```

### Define a custom output directory

```shell
kdeps -f dependencies.txt -o /path/to/output
```
