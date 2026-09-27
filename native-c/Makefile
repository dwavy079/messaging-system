# Builds the native codec for the current OS.
# Needs zlib and OpenSSL headers (macOS: brew install openssl; Linux: libssl-dev zlib1g-dev).

UNAME_S := $(shell uname -s)
CC ?= cc
CFLAGS += -O2 -Wall -Wextra -fPIC

ifeq ($(UNAME_S),Darwin)
    JAVA_HOME ?= $(shell /usr/libexec/java_home)
    OPENSSL_PREFIX ?= $(shell brew --prefix openssl 2>/dev/null)
    JNI_OS := darwin
    LIB := libmessagecodec.dylib
    SHARED := -dynamiclib
    ifneq ($(OPENSSL_PREFIX),)
        CFLAGS += -I$(OPENSSL_PREFIX)/include
        LDFLAGS += -L$(OPENSSL_PREFIX)/lib
    endif
else
    JAVA_HOME ?= $(shell dirname $$(dirname $$(readlink -f $$(which javac))))
    JNI_OS := linux
    LIB := libmessagecodec.so
    SHARED := -shared
endif

CFLAGS += -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(JNI_OS)
LDLIBS := -lz -lcrypto

all: $(LIB)

$(LIB): message_codec.c
	$(CC) $(CFLAGS) $(SHARED) -o $@ $< $(LDFLAGS) $(LDLIBS)

clean:
	rm -f libmessagecodec.so libmessagecodec.dylib

.PHONY: all clean
