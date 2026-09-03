#!/bin/sh
set -eu
helper_dir=/workspace/engines/selenium-engine-plugin/src/helper
install_dir=/opt/automation-studio/bin
mkdir -p "$install_dir"
cc -std=c17 -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now -o /tmp/as-netns-helper "$helper_dir/as_netns_helper.c"
install -o root -g root -m 0755 /tmp/as-netns-helper "$install_dir/as-netns-helper"
if "$install_dir/as-netns-helper" --digest "sha256:$(sha256sum "$install_dir/as-netns-helper" | awk '{print $1}')"; then exit 20; fi
if runuser -u automation-studio-netns -- "$install_dir/as-netns-helper" bad; then exit 21; fi
install -o root -g root -m 0755 /tmp/as-netns-helper "$install_dir/as-netns-helper"
if runuser -u automation-studio-netns -- "$install_dir/as-netns-helper" --digest "sha256:$(sha256sum "$install_dir/as-netns-helper" | awk '{print $1}')"; then exit 22; fi
setcap cap_sys_admin,cap_net_admin=ep "$install_dir/as-netns-helper"
getcap "$install_dir/as-netns-helper" | grep -F 'cap_net_admin,cap_sys_admin=ep'
run_case(){ runuser -u automation-studio-netns -- mvn -q -Dmaven.repo.local=/workspace/.m2/repository -pl engines/selenium-engine-plugin -am "-Dtest=D2cLinuxQualificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" -Das.d2c.linuxQualification=true "-Das.d2c.scenario=$1" test; }
run_case pid-race
run_case netns-race
install_script(){ printf '%s\n' '#!/bin/sh' "$1" > "$install_dir/as-netns-helper";chown root:root "$install_dir/as-netns-helper";chmod 0755 "$install_dir/as-netns-helper"; }
install_script 'cat >/dev/null; sleep 30'
run_case timeout
install_script 'cat >/dev/null; yes X | head -c 8192'
run_case overflow
install_script 'cat >/dev/null; sleep 30'
run_case interrupt
printf 'NATIVE_HELPER_NEGATIVE_MATRIX=PASS\nREAL_PROCESS_TRANSPORT_TIMEOUT=PASS\nREAL_PROCESS_TRANSPORT_OVERFLOW=PASS\nREAL_PROCESS_TRANSPORT_INTERRUPTION=PASS\nPID_NETNS_RACE=PASS\n'
