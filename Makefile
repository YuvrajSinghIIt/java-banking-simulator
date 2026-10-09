JAVAC = javac
OUT = build

.PHONY: all run test crash clean
all:
	mkdir -p $(OUT)
	$(JAVAC) -Xlint:all -d $(OUT) src/bank/*.java test/Tests.java test/CrashTest.java

run: all
	java -cp $(OUT) bank.Main

test: all
	java -cp $(OUT) Tests

clean:
	rm -rf $(OUT)

# Real crash test: start writer, kill -9 it, then verify recovery (Linux/macOS)
crash: all
	rm -f /tmp/crash.log
	java -cp $(OUT) CrashTest write /tmp/crash.log > /dev/null & PID=$$!; sleep 1; kill -9 $$PID; wait $$PID 2>/dev/null; \
	java -cp $(OUT) CrashTest verify /tmp/crash.log
