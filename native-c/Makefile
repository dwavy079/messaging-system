JAVA_HOME ?= /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
CC ?= clang
CFLAGS = -fPIC -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin

all: libmessagecodec.dylib

libmessagecodec.dylib: message_codec.c
	$(CC) $(CFLAGS) -dynamiclib -o $@ $<

clean:
	rm -f libmessagecodec.dylib
