package net.openhft.chronicle.network2.tcp;

import net.openhft.chronicle.network2.event.EventGroup;
import net.openhft.chronicle.wire.*;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

/*
Running on an i7-3970X

TextWire: Loop back echo latency was 7.4/8.9 12/20 108/925 us for 50/90 99/99.9 99.99/worst %tile
BinaryWire: Loop back echo latency was 6.6/8.0 9/11 19/3056 us for 50/90 99/99.9 99.99/worst %tile
RawWire: Loop back echo latency was 5.9/6.8 8/10 12/80 us for 50/90 99/99.9 99.99/worst %tile
 */
@RunWith(value = Parameterized.class)
public class WireTcpHandlerTest {

    private final String desc;
    private final Function<Bytes, Wire> wireWrapper;

    public WireTcpHandlerTest(String desc, Function<Bytes, Wire> wireWrapper) {
        this.desc = desc;
        this.wireWrapper = wireWrapper;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> combinations() {
        return Arrays.asList(
                new Object[]{"TextWire", (Function<Bytes, Wire>) TextWire::new},
                new Object[]{"BinaryWire", (Function<Bytes, Wire>) BinaryWire::new},
                new Object[]{"RawWire", (Function<Bytes, Wire>) RawWire::new}
        );
    }

    @Test
    public void testProcess() throws Exception {
        EventGroup eg = new EventGroup();
        eg.start();
        AcceptorEventHandler eah = new AcceptorEventHandler(0, () -> new EchoRequestHandler(wireWrapper));
        eg.addHandler(eah);

        SocketChannel[] sc = new SocketChannel[1];
        for (int i = 0; i < sc.length; i++) {
            sc[i] = SocketChannel.open(eah.getLocalAddress());
            sc[i].configureBlocking(false);
        }
//        testThroughput(sc);
        testLatency(desc, wireWrapper, sc[0]);

        eg.stop();
    }

    enum TestKey implements WireKey {
        key1, key2, key3;
    }

    static class TestData implements Marshallable {
        int key1;
        long key2;
        double key3;


        @Override
        public void writeMarshallable(Wire wire) {
            wire.write(TestKey.key1).int32(key1)
                    .write(TestKey.key2).int64(key2)
                    .write(TestKey.key3).float64(key3);
        }

        @Override
        public void readMarshallable(Wire wire) {
            wire.read(TestKey.key1).int32(i -> key1 = i)
                    .read(TestKey.key2).int64(i -> key2 = i)
                    .read(TestKey.key3).float64(i -> key3 = i);
        }
    }

    private static void testLatency(String desc, Function<Bytes, Wire> wireWrapper, SocketChannel... sockets) throws IOException {
//        System.out.println("Starting latency test");
        int tests = 500000;
        long[] times = new long[tests * sockets.length];
        int count = 0;
        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        Bytes outBytes = ByteBufferBytes.wrap(out);
        Wire outWire = wireWrapper.apply(outBytes);

        ByteBuffer in = ByteBuffer.allocateDirect(64 * 1024);
        Bytes inBytes = ByteBufferBytes.wrap(in);
        Wire inWire = wireWrapper.apply(inBytes);
        TestData td = new TestData();
        TestData td2 = new TestData();
        for (int i = -50000; i < tests; i++) {
            long now = System.nanoTime();
            for (SocketChannel socket : sockets) {
                out.clear();
                outBytes.clear();
                outBytes.writeUnsignedShort(0);
                td.key3 = td.key2 = td.key1 = i;
                td.writeMarshallable(outWire);

                outBytes.writeUnsignedShort(0, (int) outBytes.position() - 2);
                out.limit((int) outBytes.position());
                socket.write(out);
                if (out.remaining() > 0)
                    throw new AssertionError("Unable to write in one go.");
            }

            for (SocketChannel socket : sockets) {
                in.clear();
                inBytes.clear();
                while (true) {
                    int read = socket.read(in);
                    inBytes.limit(in.position());
                    if (inBytes.remaining() >= 2) {
                        int length = inBytes.readUnsignedShort(0);
                        if (inBytes.remaining() >= length + 2) {
                            inBytes.limit(length + 2);
                            inBytes.skip(2);
                            td2.readMarshallable(inWire);
                        }
                        break;
                    }
                    if (read < 0)
                        throw new AssertionError("Unable to read in one go.");
                }
                if (i >= 0)
                    times[count++] = System.nanoTime() - now;
            }
        }
        Arrays.sort(times);
        System.out.printf("%s: Loop back echo latency was %.1f/%.1f %,d/%,d %,d/%d us for 50/90 99/99.9 99.99/worst %%tile%n",
                desc,
                times[tests / 2] / 1e3,
                times[tests * 9 / 10] / 1e3,
                times[tests - tests / 100] / 1000,
                times[tests - tests / 1000] / 1000,
                times[tests - tests / 10000] / 1000,
                times[tests - 1] / 1000
        );
    }


    static class EchoRequestHandler extends WireTcpHandler {
        private final TestData td = new TestData();
        private final Function<Bytes, Wire> wireWrapper;

        EchoRequestHandler(Function<Bytes, Wire> wireWrapper) {
            this.wireWrapper = wireWrapper;
        }

        @Override
        protected void process(Wire inWire, Wire outWire) {
            td.readMarshallable(inWire);
            td.writeMarshallable(outWire);
        }

        @Override
        protected Wire createWriteFor(Bytes bytes) {
            return wireWrapper.apply(bytes);
        }
    }
}