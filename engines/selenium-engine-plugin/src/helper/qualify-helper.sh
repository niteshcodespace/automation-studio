#!/bin/sh
set -eu
export SOURCE_DATE_EPOCH=0
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc -std=c17 -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now -o "$work/first" as_netns_helper.c
cc -std=c17 -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now -o "$work/second" as_netns_helper.c
cmp "$work/first" "$work/second"
printf 'AS_NETNS_HELPER_DIGEST=sha256:%s\n' "$(sha256sum "$work/first" | awk '{print $1}')"
