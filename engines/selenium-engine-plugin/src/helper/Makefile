CC ?= cc
CFLAGS := -std=c17 -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2
LDFLAGS := -Wl,-z,relro,-z,now
PREFIX ?= /opt/automation-studio

.PHONY: all verify install clean
all: as-netns-helper

as-netns-helper: as_netns_helper.c
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

verify: as-netns-helper
	@sha256sum as-netns-helper

install: verify
	install -d -m 0755 $(DESTDIR)$(PREFIX)/bin
	install -o root -g root -m 0755 as-netns-helper $(DESTDIR)$(PREFIX)/bin/as-netns-helper
	setcap cap_sys_admin,cap_net_admin=ep $(DESTDIR)$(PREFIX)/bin/as-netns-helper
	getcap $(DESTDIR)$(PREFIX)/bin/as-netns-helper | grep -F 'cap_net_admin,cap_sys_admin=ep'

clean:
	rm -f as-netns-helper
