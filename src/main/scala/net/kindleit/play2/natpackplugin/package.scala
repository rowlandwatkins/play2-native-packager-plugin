// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.kindleit.play2

package object natpackplugin {

  import String.format
  import sbt._

  private[natpackplugin] def postInstContent(name: String, userName: String, groupName: String) = format(
"""#!/bin/sh
# postinst script for %1$s
#
# see: dh_installdeb(1)

set -e

# summary of how this script can be called:
#        * <postinst> `configure' <most-recently-configured-version>
#        * <old-postinst> `abort-upgrade' <new version>
#        * <conflictor's-postinst> `abort-remove' `in-favour' <package>
#          <new-version>
#        * <postinst> `abort-remove'
#        * <deconfigured's-postinst> `abort-deconfigure' `in-favour'
#          <failed-install-package> <version> `removing'
#          <conflicting-package> <version>
# for details, see http://www.debian.org/doc/debian-policy/ or
# the debian-policy package


case "$1" in
    configure)

        if ! getent group | grep -q "%3$s"; then
          addgroup "%3$s"
        fi

        if ! id "%2$s" > /dev/null 2>&1 ; then
            adduser --system --home "/var/lib/%1$s" --no-create-home \
                --ingroup "%3$s" --disabled-password --shell /bin/bash \
                "%2$s"
        fi

        mkdir -p "/var/log/%1$s"

        # directories needed for jenkins
        # we don't do -R because it can take a long time on big installation
        chown "%2$s:%3$s" "/var/lib/%1$s" "/var/log/%1$s"
        # we don't do "chmod 750" so that the user can choose the pemission for g and o on their own
        chmod u+rwx "/var/lib/%1$s" "/var/log/%1$s"
    ;;

    abort-upgrade|abort-remove|abort-deconfigure)
    ;;

    *)
        echo "postinst called with unknown argument \`$1'" >&2
        exit 1
    ;;
esac

# dh_installdeb will replace this with shell code automatically
# generated by other debhelper scripts.

if [ -x "/etc/init.d/%1$s" ]; then
  update-rc.d "%1$s" defaults >/dev/null
  invoke-rc.d "%1$s" start || exit $?
fi

exit 0
""", name, userName, groupName)

  private[natpackplugin] def postRmContent(name: String, userName: String) = format(
"""#!/bin/sh

set -e

case "$1" in
    purge)
        userdel "%2$s" || true
        rm -rf "/var/lib/%1$s" "/var/log/%1$s" \
               "/var/run/%1$s"
    ;;

    remove|upgrade|failed-upgrade|abort-install|abort-upgrade|disappear)
    ;;

    *)
        echo "postrm called with unknown argument \`$1'" >&2
        exit 1
    ;;
esac

if [ "$1" = "purge" ] ; then
  update-rc.d "%1$s" remove >/dev/null
fi

exit 0
""", name, userName)

  // Script to run before removing the package. (stops the service)
  private[natpackplugin] def preRmContent(initName: String) = format(
"""#!/bin/sh
set -e
if [ -x "/etc/init.d/%1$s" ]; then
  invoke-rc.d "%1$s" stop || exit $?
fi
""", initName)

  //local Play start file
  private[natpackplugin] def startFileContent(config: String) = format(
"""#!/usr/bin/env sh

exec java $* -cp "`dirname $0`/lib/*" %s play.core.server.NettyServer `dirname $0` $@
""", if (config.isEmpty()) "" else "-Dconfig.file=`dirname $0`/application.conf")

  // /etc/init.d init script
  private[natpackplugin] def initFilecontent(id: String, desc: String, user: String) = format(
"""#!/bin/bash
# "/etc/init.d/%1$s"
# debian-compatible %1$s startup script.
# Original version by: Amelia A Lewis <alewis@ibco.com>
# updates and tweaks by: Rodolfo Hansen <rhansen@kitsd.com>
#
### BEGIN INIT INFO
# Provides:          %1$s
# Required-Start:    $remote_fs $syslog $network
# Required-Stop:     $remote_fs $syslog $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Start %1$s at boot time
# Description:       Controls the %1$s Play! Framework standalone application.
### END INIT INFO

PATH=/bin:/usr/bin:/sbin:/usr/sbin

DESC="%2$s"
NAME="%1$s"
USER="%3$s"
SCRIPTNAME="/etc/init.d/$NAME"
PIDFILE="/var/run/%1$s.pid"
LOGFILE="/var/log/%1$s/console.log"

[ -r /etc/default/$NAME ] && . /etc/default/$NAME

DAEMON=/usr/bin/daemon
DAEMON_ARGS="--name=$NAME --inherit --output=$LOGFILE --pidfile=$PIDFILE"

SU=/bin/su

# Exit if the package is not installed
[ -x "$DAEMON" ] || (echo "daemon package not installed" && exit 0)

# load environments
if [ -r /etc/default/locale ]; then
  . /etc/default/locale
  export LANG LANGUAGE
elif [ -r /etc/environment ]; then
  . /etc/environment
  export LANG LANGUAGE
fi

# Load the VERBOSE setting and other rcS variables
. /lib/init/vars.sh

# Define LSB log_* functions.
# Depend on lsb-base (>= 3.0-6) to ensure that this file is present.
. /lib/lsb/init-functions

# Make sure we run as root, since setting the max open files through
# ulimit requires root access
if [ `id -u` -ne 0 ]; then
    echo "The $NAME init script can only be run as root"
    exit 1
fi


check_tcp_port() {
    local service=$1
    local assigned=$2
    local default=$3

    if [ -n "$assigned" ]; then
        port=$assigned
    else
        port=$default
    fi

    count=`netstat --listen --numeric-ports | grep \:$port[[:space:]] | grep -c . `

    if [ $count -ne 0 ]; then
        echo "The selected $service port ($port) seems to be in use by another program "
        echo "Please select another port to use for $NAME"
        return 1
    fi
}

#
# Function that starts the daemon/service
#
do_start()
{
    # the default location is "/var/run/%1$s.pid" but the parent directory needs to be created
    mkdir `dirname $PIDFILE` > /dev/null 2>&1 || true
    chown $USER `dirname $PIDFILE`
    # Return
    #   0 if daemon has been started
    #   1 if daemon was already running
    #   2 if daemon could not be started
    $DAEMON $DAEMON_ARGS --running && return 1

    # Verify that the jenkins port is not already in use, winstone does not exit
    # even for BindException
    check_tcp_port "http" "$HTTP_PORT" "9000" || return 1

    # If the var MAXOPENFILES is enabled in "/etc/default/%1$s" then set the max open files to the
    # proper value
    if [ -n "$MAXOPENFILES" ]; then
        [ "$VERBOSE" != no ] && echo Setting up max open files limit to $MAXOPENFILES
        ulimit -n $MAXOPENFILES
    fi

    # --user in daemon doesn't prepare environment variables like HOME, USER, LOGNAME or USERNAME,
    # so we let su do so for us now
    $SU -l $USER --shell=/bin/bash -c "$DAEMON $DAEMON_ARGS -- /var/lib/%1$s/start $PLAY_ARGS" || return 2
}


#
# Verify that all jenkins processes have been shutdown
# and if not, then do killall for them
#
get_running()
{
    return `ps -U $USER --no-headers -f | egrep -e '(java|daemon)' | grep -c . `
}

force_stop()
{
    get_running
    if [ $? -ne 0 ]; then
        killall -u $USER java daemon || return 3
    fi
}

# Get the status of the daemon process
get_daemon_status()
{
    $DAEMON $DAEMON_ARGS --running || return 1
}


#
# Function that stops the daemon/service
#
do_stop()
{
    # Return
    #   0 if daemon has been stopped
    #   1 if daemon was already stopped
    #   2 if daemon could not be stopped
    #   other if a failure occurred
    get_daemon_status
    case "$?" in
  0)
      $DAEMON $DAEMON_ARGS --stop || return 2
        # wait for the process to really terminate
        for n in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
            sleep 1
            $DAEMON $DAEMON_ARGS --running || break
        done
        if get_daemon_status; then
          force_stop || return 3
        fi
      ;;
  *)
      force_stop || return 3
      ;;
    esac

    # Many daemons don't delete their pidfiles when they exit.
    rm -f $PIDFILE
    return 0
}

case "$1" in
  start)
    log_daemon_msg "Starting $DESC" "$NAME"
    do_start
    case "$?" in
        0|1) log_end_msg 0 ;;
        2) log_end_msg 1 ;;
    esac
    ;;
  stop)
    log_daemon_msg "Stopping $DESC" "$NAME"
    do_stop
    case "$?" in
        0|1) log_end_msg 0 ;;
        2) log_end_msg 1 ;;
    esac
    ;;
  restart|force-reload)
    #
    # If the "reload" option is implemented then remove the
    # 'force-reload' alias
    #
    log_daemon_msg "Restarting $DESC" "$NAME"
    do_stop
    case "$?" in
      0|1)
        do_start
        case "$?" in
          0) log_end_msg 0 ;;
          1) log_end_msg 1 ;; # Old process is still running
          *) log_end_msg 1 ;; # Failed to start
        esac
        ;;
      *)
    # Failed to stop
  log_end_msg 1
  ;;
    esac
    ;;
  status)
  get_daemon_status
  case "$?" in
   0)
    echo "$DESC is running with the pid `cat $PIDFILE`"
    rc=0
    ;;
  *)
    get_running
    procs=$?
    if [ $procs -eq 0 ]; then
      echo -n "$DESC is not running"
      if [ -f $PIDFILE ]; then
        echo ", but the pidfile ($PIDFILE) still exists"
        rc=1
      else
        echo
        rc=3
      fi

    else
      echo "$procs instances of jenkins are running at the moment"
      echo "but the pidfile $PIDFILE is missing"
      rc=0
    fi

    exit $rc
    ;;
  esac
  ;;
  *)
    echo "Usage: $SCRIPTNAME {start|stop|status|restart|force-reload}" >&2
    exit 3
    ;;
esac

exit 0
""", id, desc, user)

  // Common helper methods
  private[natpackplugin] def chmod(file: File, perms: String): Unit =
    Process(Seq("chmod", perms, file.getAbsolutePath)).! match {
      case 0 ⇒ ()
      case n ⇒ sys.error("Error running chmod %s %s" format(perms, file))
    }

  private[natpackplugin] def debFile(name: String, content: => String)(dir: File) = {
    val file = dir / "DEBIAN" / name
    IO.write(file, content)
    chmod(file, "0755")
    file
  }

  private[natpackplugin] def debFile1[T](name: String, content: (T) => String)(dir: File, t: T) =
    debFile(name, content(t))(dir)

  private[natpackplugin] def debFile2[T, U](name: String, content: (T, U) => String)(dir: File, t: T, u: U) =
    debFile(name, content(t, u))(dir)

  private[natpackplugin] def debFile3[T, U, V](name: String, content: (T, U, V) => String)(dir: File, t: T, u: U, v: V) =
    debFile(name, content(t, u, v))(dir)

}
