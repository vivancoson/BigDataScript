#!/bin/sh

# Delete old jar
mkdir $HOME/.bds
rm -f $HOME/.bds/BigDataScript.jar $HOME/.bds/bds

# Build Jar file
echo Building JAR file
ant 

# Build go program
echo
echo Building bds wrapper: Compiling GO program
cd go/bds/
go clean
go build
go fmt
cd -

