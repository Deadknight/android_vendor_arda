#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

constexpr char kSocketName[] = "arda_input_bridge";
constexpr char kDefaultPayload[] = "PING\n";

bool WriteAll(int fd, const char* data, size_t size) {
    while (size > 0) {
        const ssize_t written = write(fd, data, size);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        data += written;
        size -= static_cast<size_t>(written);
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    const char* payload = argc >= 2 ? argv[1] : kDefaultPayload;

    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        perror("socket");
        return 2;
    }

    sockaddr_un address = {};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';

    const size_t name_length = strlen(kSocketName);
    if (name_length > sizeof(address.sun_path) - 1) {
        fprintf(stderr, "socket name too long\n");
        close(fd);
        return 3;
    }

    memcpy(address.sun_path + 1, kSocketName, name_length);
    const socklen_t address_length =
            static_cast<socklen_t>(
                    offsetof(sockaddr_un, sun_path) + 1 + name_length);

    if (connect(
                fd,
                reinterpret_cast<const sockaddr*>(&address),
                address_length) != 0) {
        perror("connect");
        close(fd);
        return 4;
    }

    if (!WriteAll(fd, payload, strlen(payload))) {
        perror("write");
        close(fd);
        return 5;
    }

    shutdown(fd, SHUT_WR);
    usleep(200000);
    close(fd);

    printf(
            "ARDA_INPUT_BRIDGE_PROBE=PASS uid=%d socket=%s bytes=%zu\n",
            getuid(),
            kSocketName,
            strlen(payload));
    return 0;
}
