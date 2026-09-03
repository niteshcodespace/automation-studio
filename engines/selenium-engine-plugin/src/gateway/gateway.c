#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>

static volatile sig_atomic_t running = 1;
static void stop(int signal_number) { (void) signal_number; running = 0; }

int main(void) {
    static const char enabled[] = "1\n";
    int fd = open("/proc/sys/net/ipv4/ip_forward", O_WRONLY | O_CLOEXEC);
    if (fd < 0 || write(fd, enabled, sizeof(enabled) - 1) != sizeof(enabled) - 1 || close(fd) != 0)
        return 70;
    if (signal(SIGTERM, stop) == SIG_ERR || signal(SIGINT, stop) == SIG_ERR) return 71;
    while (running) pause();
    return 0;
}
