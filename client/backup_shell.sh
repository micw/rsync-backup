#!/bin/bash
#
# Limited shell for backup access. Add the following line to /root/.ssh/authorized_keys:
# command="/backup/backup_shell.sh",no-port-forwarding,no-X11-forwarding,no-pty [BACKUP-SSH-PUBLIC-KEY]


CMD_NICE="/usr/bin/nice -n 19 /usr/bin/ionice -c3"
CONFIG=$( dirname $0 )/backup.conf
CMD_RSYNC=/usr/bin/rsync

if [ -f ${CONFIG} ]; then
  source ${CONFIG}
fi

case  "$SSH_ORIGINAL_COMMAND" in
  NOOP)
    echo "OK"
  ;;
  PRE_BACKUP)
    ${CMD_NICE} /backup/pre_backup.sh
  ;;
  PROXY_*)
    PROXY_H="${SSH_ORIGINAL_COMMAND}_HOST"
    PROXY_P="${SSH_ORIGINAL_COMMAND}_PORT"
    PROXY_HOST=${!PROXY_H}
    PROXY_PORT=${!PROXY_P}
    if [ -z "$PROXY_HOST" -o -z "$PROXY_PORT" ]; then
      echo "No such proxy configured: $SSH_ORIGINAL_COMMAND";
      exit 1
    fi
    /bin/nc $PROXY_HOST $PROXY_PORT 2>/dev/null
  ;;
  rsync*)
    RSYNC_PATH="${SSH_ORIGINAL_COMMAND##* }"
    RSYNC_PATH=$( echo -n "${RSYNC_PATH}" | sed 's/[^a-zA-Z0-9_-]//g' )
    ${CMD_NICE} ${CMD_RSYNC} --server --sender -vlogDtprSe.iLsf --numeric-ids . "/backup/volumes/${RSYNC_PATH}/"
  ;;
  *)
    echo "Rejected -> '*' : $SSH_ORIGINAL_COMMAND" 1
    exit 1
  ;;
esac
