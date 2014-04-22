#!/bin/bash

cd $( dirname $0 )

/usr/bin/java -cp RSyncBackup.jar org.github.rsyncbackup.RSyncBackup $*
