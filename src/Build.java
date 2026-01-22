
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.TreeSet;

public class Build {

    private static final int rank = 0;
    private static final int suit = 1;

    private static final int[][] card = new int[52][2];
    private static final int[][] player = new int[1081][2];
    private static final int[][] community = new int[2598960][5];

    private static final int[] table = new int[5];
    private static final int[] count = new int[10];
    private static final int[] types = new int[1081];
    private static final int[][] sort = new int[1081][2];
    private static final int[][] hands = new int[1081][5];
    private static final int[][] ranked = new int[1081][2];

    private static int main_index = 0;
    private static int save_index = 0;

    private static final Arena arena = Arena.ofShared();
    private static final Path river = Path.of("river.bin");
    private static final Path lookup = Path.of("lookup.bin");

    private static final int batch_size = 2652;
    private static final int[][] save_hands = new int[batch_size][1326];

    public static void main(String[] args)
    {
        build_lookup();
    }

    private static void build_lookup() {
        long time = 0;
        long total = 0;

        start();
        get_community();

        TreeSet<Integer> unique_scores = new TreeSet<>();
        HashMap<Integer, Short> score_to_rank = new HashMap<>();

        for (int n = 0; n < community.length; n++) {
            long a = System.nanoTime();

            main_index = n;
            save_index = main_index % batch_size;

            for (int i = 0; i < table.length; i++) {
                table[i] = community[n][i];
            }

            reset();
            get_pocket();

            flush();
            straight();
            some_kind_of();
            sort_hands();

            for (int j = 0; j < 1326; j++) {
                unique_scores.add(save_hands[save_index][j]);
            }

            time += (System.nanoTime() - a);

            if (save_index == (batch_size - 1)) {
                append_hands();
                clear();

                System.out.print("Done: " + (main_index / batch_size) + " of " + (2598960 / batch_size) + " - ");
                System.out.print("Time: " + (time / 1_000_000) + " ms\n");

                total += time;
                time = 0;
            }
        }

        System.out.print("\n\nBuilt river.bin in: " + (total / 1_000_000_000) + " seconds\n\n");

        if (unique_scores.size() < Short.MAX_VALUE) {
            short score_rank = 0;
            for (int score : unique_scores) {
                score_to_rank.put(score, score_rank++);
            }

            int values = 1326 * batch_size;
            int int_size = 4 * values;
            int short_size = 2 * values;

            int[] int_buffer = new int[values];
            short[] short_buffer = new short[values];

            try {
                FileChannel in_file = FileChannel.open(
                        river,
                        StandardOpenOption.READ
                );

                MemorySegment river_table = in_file.map(
                        FileChannel.MapMode.READ_ONLY,
                        0, in_file.size(), arena
                );

                MemorySegment ints = MemorySegment.ofArray(int_buffer);

                int batches = (int) (in_file.size() / int_size);

                FileChannel out_file = FileChannel.open(
                        lookup,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );

                ByteBuffer out_buf = ByteBuffer.allocate(short_size).order(ByteOrder.LITTLE_ENDIAN);
                ShortBuffer short_out = out_buf.asShortBuffer();

                long total2 = 0;

                for (int i = 0, off = 0; i < batches; i++, off += int_size) {
                    long a2 = System.nanoTime();

                    MemorySegment.copy(river_table, off, ints, 0, int_size);

                    for (int j = 0; j < values; j++) {
                        short_buffer[j] = score_to_rank.get(int_buffer[j]);
                    }

                    short_out.position(0);
                    short_out.put(short_buffer);

                    out_buf.position(0);
                    out_buf.limit(short_size);

                    while (out_buf.hasRemaining()) {
                        out_file.write(out_buf);
                    }

                    long c2 = System.nanoTime() - a2;
                    total2 += c2;

                    System.out.print("Done: " + (i + 1) + " / " + batches + " - ");
                    System.out.print("Time: " + (c2 / 1_000_000) + " ms\n");
                }

                System.out.print("\n\nBuilt lookup.bin in: " + (total2 / 1_000_000_000) + " seconds\n\n");

                out_file.close();
                in_file.close();

                try {
                    Files.delete(river);
                    System.out.print("Deleted river.bin\n\n");
                } catch (Exception e) {
                    e.printStackTrace();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }



    private static void start() {
        for (int i = 0; i < 52; i++) {
            card[i][rank] = i / 4;
            card[i][suit] = i % 4;
        }
    }

    private static void reset() {
        for (int i = 0; i < count.length; i++) {
            count[i] = 0;
        }

        for (int i = 0; i < types.length; i++) {
            types[i] = 0;
        }

        for (int i = 0; i < sort.length; i++) {
            sort[i][0] = 0;
            sort[i][1] = 0;
        }

        for (int i = 0; i < hands.length; i++) {
            hands[i][0] = 0;
            hands[i][1] = 0;
            hands[i][2] = 0;
            hands[i][3] = 0;
            hands[i][4] = 0;
        }

        for (int i = 0; i < ranked.length; i++) {
            ranked[i][0] = 0;
            ranked[i][1] = 0;
        }

        for (int i = 0; i < player.length; i++) {
            player[i][0] = 0;
            player[i][1] = 0;
        }
    }

    private static void clear() {
        for (int i = 0; i < save_hands.length; i++) {
            for (int j = 0; j < save_hands[i].length; j++) {
                save_hands[i][j] = 0;
            }
        }
    }

    private static void get_pocket() {
        int n = 0;
        boolean[] skip = new boolean[52];

        for (int card : table) {
            skip[card] = true;
        }

        for (int a = 0; a < 52; a++) {
            if (skip[a]) continue;

            for (int b = a + 1; b < 52; b++) {
                if (skip[b]) continue;
                player[n][0] = a;
                player[n][1] = b;
                n++;
            }
        }
    }

    private static void get_community() {
        int n = 0;

        for (int a = 0; a < 52; a++) {
            for (int b = a + 1; b < 52; b++) {
                for (int c = b + 1; c < 52; c++) {
                    for (int d = c + 1; d < 52; d++) {
                        for (int e = d + 1; e < 52; e++) {
                            community[n][0] = a;
                            community[n][1] = b;
                            community[n][2] = c;
                            community[n][3] = d;
                            community[n][4] = e;
                            n++;
                        }
                    }
                }
            }
        }
    }



    private static void flush() {
        int comm = 0;
        int play = 0;

        int suit_count = 0;
        int flush_suit = 0;

        int[] hand_cards = new int[5];
        int[] table_count = new int[4];
        int[] comm_cards = new int[13];
        int[] play_cards = new int[13];

        for (int n = 0; n < table.length; n++) {
            table_count[card[table[n]][suit]]++;
        }

        for (int n = 0; n < table_count.length; n++) {
            if (table_count[n] >= 3) {
                flush_suit = n;
                suit_count = table_count[n];

                for (int c = 0, r, s; c < table.length; c++) {
                    r = card[table[c]][rank];
                    s = card[table[c]][suit];

                    if (s == flush_suit) {
                        comm |= 1 << (r + 1);
                        comm_cards[r] = table[c];
                    }
                }
                break;
            }
        }

        if (suit_count < 3) return;

        int high = 0;
        int count = 0;

        int ace = 1 << 13;
        int five = 0x3E00;
        boolean SF;

        for (int p = 0; p < player.length; p++) {
            for (int i = 0; i < 13; i++) {
                play_cards[i] = comm_cards[i];
            }

            play = comm;
            count = suit_count;

            int c1 = player[p][0];
            int c2 = player[p][1];

            int s1 = card[c1][suit];
            int s2 = card[c2][suit];

            int r1 = card[c1][rank];
            int r2 = card[c2][rank];

            if (s1 == flush_suit) {
                count++;
                play |= 1 << (r1 + 1);
                play_cards[r1] = c1;
            }

            if (s2 == flush_suit) {
                count++;
                play |= 1 << (r2 + 1);
                play_cards[r2] = c2;
            }

            if (count < 5) continue;

            SF = false;
            if ((play & ace) != 0) play |= 1;

            for (int i = 0, n = 0, mask; n < 5; i++) {
                mask = 8192 >> i;
                if ((play & mask) == mask) {
                    hand_cards[n++] = play_cards[12 - i];
                }
            }

            for (int i = 0, mask; i < 10; i++) {
                mask = five >> i;
                if ((play & mask) == mask) {
                    SF = true;
                    high = 13 - i - 1;

                    if (high != 3) {
                        for (int b = high, a = 0; b > high - 5; ) {
                            hand_cards[a++] = play_cards[b--];
                        }
                    } else {
                        for (int b = high, a = 0; b > high - 4; ) {
                            hand_cards[a++] = play_cards[b--];
                        }
                        hand_cards[4] = play_cards[12];
                    }
                    break;
                }
            }

            if (SF) {
                if (high == 12) {
                    save_hand(p, hand_cards, 9);
                } else {
                    save_hand(p, hand_cards, 8);
                }
            } else {
                save_hand(p, hand_cards, 5);
            }
        }
    }

    private static void straight() {
        int comm = 0;
        int play = 0;
        int high = 0;

        int ace = 1 << 13;
        int five = 0x3E00;

        int[] hand_cards = new int[5];
        int[] comm_cards = new int[13];
        int[] play_cards = new int[13];

        for (int n = 0, r; n < table.length; n++) {
            r = card[table[n]][rank];
            comm |= 1 << (r + 1);
            comm_cards[r] = table[n];
        }

        for (int p = 0; p < player.length; p++) {
            for (int i = 0; i < 13; i++) {
                play_cards[i] = comm_cards[i];
            }

            int r1 = card[player[p][0]][rank];
            int r2 = card[player[p][1]][rank];

            play = comm;
            play |= 1 << (r1 + 1);
            play |= 1 << (r2 + 1);

            play_cards[r1] = player[p][0];
            play_cards[r2] = player[p][1];

            if ((play & ace) != 0) play |= 1;

            for (int i = 0, mask; i < 10; i++) {
                mask = five >> i;
                if ((play & mask) == mask) {
                    high = 13 - i - 1;

                    if (high != 3) {
                        for (int b = high, a = 0; b > high - 5; ) {
                            hand_cards[a++] = play_cards[b--];
                        }
                    } else {
                        for (int b = high, a = 0; b > high - 4; ) {
                            hand_cards[a++] = play_cards[b--];
                        }
                        hand_cards[4] = play_cards[12];
                    }

                    save_hand(p, hand_cards, 4);
                    break;
                }
            }
        }
    }

    private static void some_kind_of() {
        int[] hand = new int[5];
        int[] type = new int[5];
        int[] comm = new int[13];
        int[] play = new int[13];

        for (int i = 0; i < 5; i++) {
            int n = table[i] + 1;
            int r = card[n - 1][rank];

            if (comm[r] == 0) {
                comm[r] = n;
            } else {
                comm[r] <<= 6;
                comm[r] |= n;
            }
        }

        for (int p = 0; p < player.length; p++) {
            int hand_type = 0;

            for (int i = 0; i < 5; i++) {
                type[i] = 0;
            }

            for (int i = 0; i < 13; i++) {
                play[i] = comm[i];
            }

            for (int i = 0; i < 2; i++) {
                int n = player[p][i] + 1;
                int r = card[n - 1][rank];

                if (play[r] == 0) {
                    play[r] = n;
                } else {
                    play[r] <<= 6;
                    play[r] |= n;
                }
            }

            sort(13, play, false);

            for (int i = 0, n = 0; n < 5; ) {
                if (play[i] == 0) {
                    type[i++] = n;
                } else {
                    hand[n++] = (play[i] & 0b111111) - 1;
                    play[i] >>= 6;
                }
                if (n == 5) type[i] = n;
            }

            for (int i = 0; i < 4; i++) {
                type[i + 1] = type[i + 1] - type[i];
            }

            if (type[0] == 1) {
                hand_type = 0;
            } else if (type[0] == 2 && type[1] == 1) {
                hand_type = 1;
            } else if (type[0] == 2 && type[1] == 2) {
                hand_type = 2;
            } else if (type[0] == 3 && type[1] == 1) {
                hand_type = 3;
            } else if (type[0] == 3 && type[1] == 2) {
                hand_type = 6;
            } else if (type[0] == 4) {
                hand_type = 7;
            }

            save_hand(p, hand, hand_type);
        }
    }

    private static void sort_hands() {
        final int MASK = 15;
        final int PASSES = 6;
        int[][] output = new int[hands.length][2];

        for (int p = 0; p < hands.length; p++) {
            int number = types[p];
            for (int c = 0; c < 5; c++) {
                number <<= 4;
                number |= card[hands[p][c]][rank];
            }
            sort[p][0] = number;
            sort[p][1] = p;
        }

        for (int pass = 0; pass < PASSES; pass++) {
            int[] bins = new int[16];

            for (int i = 0; i < hands.length; i++) {
                int digit = (sort[i][0] >>> (4 * pass)) & MASK;
                bins[digit]++;
            }

            for (int i = 1; i < 16; i++) {
                bins[i] += bins[i - 1];
            }

            for (int i = hands.length - 1; i >= 0; i--) {
                int digit = (sort[i][0] >>> (4 * pass)) & MASK;
                int pos = --bins[digit];
                output[pos][0] = sort[i][0];
                output[pos][1] = sort[i][1];
            }

            for (int i = 0; i < hands.length; i++) {
                sort[i][0] = output[i][0];
                sort[i][1] = output[i][1];
            }
        }

        for (int i = 0, j = hands.length - 1; i < j; i++, j--) {
            int[] tmp = sort[i];
            sort[i] = sort[j];
            sort[j] = tmp;
        }

        for (int i = 0; i < hands.length; i++) {
            ranked[i][0] = sort[i][0];
            ranked[i][1] = sort[i][1];
        }

        int[] save = new int[1326];

        for (int n = 0; n < 1081; n++) {
            int v = ranked[n][0];
            int p = ranked[n][1];

            int c1 = player[p][0];
            int c2 = player[p][1];

            int x = get_index(c1, c2);

            save[x] = v;
        }

        for (int n = 0; n < 1326; n++) {
            save_hands[save_index][n] = save[n];
        }
    }

    public static void append_hands() {
        try {
            int allocate = 4 * save_hands.length * 1326;

            FileChannel fc = FileChannel.open(
                    river,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );

            ByteBuffer buf = ByteBuffer.allocate(allocate).order(ByteOrder.LITTLE_ENDIAN);
            IntBuffer ints = buf.asIntBuffer();

            for (int n = 0; n < save_hands.length; n++) {
                ints.put(save_hands[n]);
            }

            buf.limit(4 * ints.position()).position(0);
            while (buf.hasRemaining()) fc.write(buf);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    public static int get_index(int a, int b) {
        if (a < b) {
            return (51 * a - (a * (a - 1)) / 2) + b - a - 1;
        } else {
            return (51 * b - (b * (b - 1)) / 2) + a - b - 1;
        }
    }

    static int C(int n, int k) {
        switch (k) {
            case 0:  return 1;
            case 1:  return n;
            case 2:  return n * (n - 1) / 2;
            case 3:  return n * (n - 1) * (n - 2) / 6;
            case 4:  return n * (n - 1) * (n - 2) * (n - 3) / 24;
            default: return n * (n - 1) * (n - 2) * (n - 3) * (n - 4) / 120;
        }
    }

    private static int get_index(int stage, int[] cards) {
        int idx = 0;
        int pre = -1;

        int[] copy = new int[5];
        for (int i = 0; i < stage; i++) {
            copy[i] = cards[i];
        }
        sort(stage, copy, true);

        for (int i = 0, cur, r, l, u; i < stage; i++) {
            cur = copy[i];
            r = stage - i;
            l = pre + 1;
            u = cur - 1;

            if (l <= u) {
                idx += C(52 - l, r) - C(51 - u, r);
            }
            pre = cur;
        }
        return idx;
    }



    private static void sort(int length, int[] arr, boolean rev) {
        if (rev) {
            for (int i = 1; i < length; i++) {
                int j = i - 1;
                int k = arr[i];

                while (j >= 0 && arr[j] > k) {
                    arr[j + 1] = arr[j];
                    j--;
                }
                arr[j + 1] = k;
            }
        } else {
            for (int i = 1; i < length; i++) {
                int j = i - 1;
                int k = arr[i];

                while (j >= 0 && arr[j] < k) {
                    arr[j + 1] = arr[j];
                    j--;
                }
                arr[j + 1] = k;
            }
        }
    }

    private static void save_hand(int p, int[] hand_cards, int type) {
        if (type >= types[p]) {
            for (int i = 0; i < 5; i++) {
                hands[p][i] = hand_cards[i];
            }
            types[p] = type;
        }
    }
}
