#!/bin/bash

CONFIG=$( dirname $0 )/backup.conf

if [ ! -f ${CONFIG} ]; then
  echo "No backup config not found: ${CONFIG}"
  exit 0
fi

# Defaults (können in der config überschrieben werden)
CMD_MYSQLSHOW=/usr/bin/mysqlshow
CMD_MYSQLDUMP=/usr/bin/mysqldump
CMD_NICE="/usr/bin/nice -n 19 /usr/bin/ionice -c3"
MYSQL_ARGS="-uroot"
MYSQLDUMP_ARGS="--single-transaction --max_allowed_packet=500M"
DRY_RUN=1
ZABBIX_HOST=argus.evermind.de
ZABBIX_ITEM_HOSTNAME=$( hostname )
ZABBIX_SENDER_CMD=/usr/bin/zabbix_sender

source $CONFIG

ERR=0

log() {
  echo "$(/bin/date +"%Y-%m-%d %H:%M:%S")  $*"
}
check_error() {
  EXITCODE=$?
  if [ $EXITCODE -ne 0 ]; then
    echo "ERROR: Last command exited with error code $EXITCODE"
    $ERR=$EXITCODE
  fi
}
eval_or_print() {
  if [ ${DRY_RUN} -eq "0" ]; then
    eval "$1"
  else
    log "(DRY_RUN): $1"
  fi
}

if [ ! -z "${MYSQL_DIR}" ]; then
  log "Backup up mysql database to ${MYSQL_DIR}"
  dbs="$( ${CMD_MYSQLSHOW} ${MYSQL_ARGS} )"
  check_error
  dbdumpcount=0
  dbcount=$(( $( echo "$dbs" | wc -l ) - 3))
  log " Dumping CREATE DATABASE commands"
      CMD="${CMD_NICE} ${CMD_MYSQLDUMP} ${MYSQL_ARGS} ${MYSQLDUMP_ARGS} -A --no-data --add-drop-database --no-create-info"
      if [ ${DRY_RUN} -eq "0" ]; then
        ${CMD} | bzip2 -c > "${MYSQL_DIR}/MYSQL_ALL_DATABASES.sql.bz2"
        check_error
      else
        log "(DRY_RUN): ${CMD}"
      fi
  log " Found ${dbcount} databases:"
  for db in $( echo "$dbs" | tail -n $dbcount | head -n $(( dbcount -1 )) | awk '{ print $2  }' ); do
    if [ $db == "information_schema" ]; then
      log "  $db - SKIP!"
    else
      log "  $db"
      CMD="${CMD_NICE} ${CMD_MYSQLDUMP} ${MYSQL_ARGS} ${MYSQLDUMP_ARGS} -c --add-drop-table --databases $db"
      if [ ${DRY_RUN} -eq "0" ]; then
        ${CMD} | bzip2 -c > "${MYSQL_DIR}/MYSQL_${db}.sql.bz2"
        check_error
      else
        log "(DRY_RUN): ${CMD}"
      fi
      dbdumpcount=$(( dbdumpcount + 1 ))
    fi
  done
  if [ ! -z ${ZABBIX_HOST} ]; then
    eval_or_print "${ZABBIX_SENDER_CMD} -z ${ZABBIX_HOST} -s ${ZABBIX_ITEM_HOSTNAME} -k 'backup.dbdumps[mysql]' -o '${dbdumpcount}'"
  fi
  log "done."
fi

if [ ! -z "${POSTGRES_DIR}" ]; then
  log "Backup up postgres database to ${POSTGRES_DIR}"
  DBs=$( psql -U root -q -c "\l" postgres | sed -n 4,/\eof/p | grep -v rows\) | awk {'print $1'} | grep -v '^|' | grep -v '^ *$' )
  check_error
  dbdumpcount=0
  dbcount=$(( $( echo "$DBs" | wc -l ) ))
  log " Found ${dbcount} databases:"
  for db in $DBs; do
    if [ $db == "template0" ] || [ $db == "template1" ]; then
      log "  $db - SKIP!"
    else
      log "  $db"
      CMD="${CMD_NICE} /usr/bin/pg_dump -U root --format=p -b --inserts ${db}"
      if [ ${DRY_RUN} -eq "0" ]; then
        ${CMD} | bzip2 -c > "${POSTGRES_DIR}/POSTGRES_${db}.sql.gz"
        check_error
      else
        log "(DRY_RUN): ${CMD}"
      fi
      dbdumpcount=$(( dbdumpcount + 1 ))
    fi
  done
  if [ ! -z ${ZABBIX_HOST} ]; then
    eval_or_print "${ZABBIX_SENDER_CMD} -z ${ZABBIX_HOST} -s ${ZABBIX_ITEM_HOSTNAME} -k 'backup.dbdumps[postgres]' -o '${dbdumpcount}'"
  fi
  log "done."
fi

exit $ERR
