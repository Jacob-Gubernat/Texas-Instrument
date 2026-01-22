import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main
{
    static int stage = 3;
    static int players = 4;
    static boolean first = true;

    static int[] table = new int[5];
    static int[] main_deck = new int[52];
    static long[] cards = new long[1176];
    static long[] index = new long[1176];

    private static Random random = new Random();
    static int[][] player = new int[players][2];
    static int[][] lookup = new int[1176][1326];
    static int[][] river = new int[players][1085];

    private static MemorySegment lookup_file;
    private static final Arena arena = Arena.ofShared();
    private static Path file_name = Path.of("lookup.bin");

    final static int[] card_count = {0, 0, 0, 47, 46, 45};
    final static long[] river_count = {0, 0, 0, 1081, 46, 1};



    private static final String[] suit_name = {"♠", "♥", "♦", "♣"};
    private static final String[] street_name = {"Pre-Flop", "", "", "Flop", "Turn", "River"};
    private static final String[] rank_name = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"};



    public static void main(String[] args) throws Exception
    {
        throughput_test();
        //accuracy_profile_intervals(10_000_000L, 100, 100, "accuracy_profile");
    }


    static long[] run_batch(int p, long games, int total)
    {
        int p1 = player[p][0];
        int p2 = player[p][1];

        long r_count = river_count[stage];
        long game_per_riv = games / r_count;
        int dealt = 45 - (2 * (total - 1));

        int[] deck = new int[45];
        int[] score;
        long[] results = new long[total];
        long pocket = (1L << p1) | (1L << p2);

        SplittableRandom rng = new SplittableRandom();

        int riv;
        long used;
        int c1, c2;
        int villan;
        boolean lost;
        int hero, tie;

        for (int r = 0; r < r_count; r++)
        {
            riv = river[p][r];
            score = lookup[riv];
            used = pocket | cards[riv];
            hero = score[get_index(p1, p2)];

            for (int i = 0, n = 0; i < 52; i++)
            {
                if ((used & (1L << i)) == 0) deck[n++] = i;
            }

            for (long g = 0; g < game_per_riv; g++)
            {
                lost = false;
                tie = 0;

                for (int a = 45, b; a > dealt;)
                {
                    b = rng.nextInt(a--);
                    c1 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c1;

                    b = rng.nextInt(a--);
                    c2 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c2;

                    villan = score[get_index(c1, c2)];

                    if (villan > hero)
                    {
                        lost = true;
                        break;
                    }
                    if (villan == hero) tie++;
                }

                if (!lost) results[tie]++;
            }
        }

        return results;
    }
    static double[][] simulate(int[] community, int[][] pocket,  int street, int total, long games)
    {
        if (first)
        {
            build_maps();
            first = false;
        }
        for (int i = 0; i < community.length; i++)
        {
            table[i] = community[i];
        }
        for (int i = 0; i < pocket.length; i++)
        {
            player[i][0] = pocket[i][0];
            player[i][1] = pocket[i][1];
        }
        for (int i = 3; i <= street && i <= 5; i++)
        {
            stage = street;
            if (street == 3) {
                load_lookup();
            }
            update_decks();
        }

        long r_count = river_count[stage];
        double simulated = games - games % r_count;

        long[][] results = new long[players][total];
        double[][] ratios = new double[players][total];

        List<Callable<long[]>> jobs = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(players);

        try {
            for (int p = 0; p < players; p++)
            {
                final int play = p;
                jobs.add(() -> run_batch(play, games, total));
            }

            List<Future<long[]>> futures = pool.invokeAll(jobs);

            for (int i = 0; i < futures.size(); i++)
            {
                results[i] = futures.get(i).get();
            }

            pool.shutdown();

            for (int i = 0; i < players; i++)
            {
                for (int j = 0; j < total; j++)
                {
                    ratios[i][j] = (double) results[i][j] / simulated;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return ratios;
    }



    static void accuracy_test()
    {

        long iters = 1L;
        long games = 1_000_000L;
        double[] err = new double[players];
        double[] acc = new double[players];
        double[][] odds = new double[players][players];

        int[] test = {
                36,
                27,
                16,
                9,
                4
        };
        int[][] plays = {
                { 0,  8},
                {40, 44},
                {38, 39},
                {13, 21},
                {10, 50},
                {31, 34},
                {11, 19},
                { 5, 25},
        };
        double[] exact = {
                0.81176614,
                0.95050505,
                0.56289382
        };
        for (int i = 0; i < table.length; i++)
        {
            table[i] = test[i];

        }
        for (int i = 0; i < player.length; i++)
        {
            player[i][0] = plays[i][0];
            player[i][1] = plays[i][1];
        }

        build_maps();
        stage = 3;
        update_decks();
        load_lookup();
        stage = 4;
        update_decks();
        stage = 5;
        update_decks();

        System.out.println("\nRunning!");

        long one = System.nanoTime();

        for (int i = 0; i < iters; i++)
        {
            try
            {
                odds = simulate_test(games);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        long two = System.nanoTime();
        double time = (two - one) / (iters * 1_000_000.0);
        double rate = round( (players * games / (1000 * time) ), 3);

        System.out.println("\n");
        System.out.printf("Game: %d\n", games);
        System.out.printf("Time: %.2f ms\n", time);
        System.out.printf("Rate: %.2f M/s\n", rate);

        for (int i = 0; i < players; i++)
        {
            System.out.print("\nPlayer " + i + ": ");
            for (int j = 0; j < players; j++)
            {
                System.out.printf("%.5f  ", odds[i][j]);
            }
        }
        System.out.println("\n");

        for (int i = 0; i < exact.length; i++)
        {
            acc[i] = 100.0 - 100.0 * Math.abs(odds[i][0] - exact[i]);
            System.out.printf("Win Odds Accuracy %d: %.4f\n", i, acc[i]);
        }
        System.out.println("\n");
    }
    static void throughput_test()
    {
        start();
        build_maps();

        long times = 10L;
        long games = 1000_000L;

        for (int i = 0; i < times; i++)
        {
            long t1 = System.nanoTime();

            reset();
            shuffle();
            deal_cards();

            for (int n = 3; n < 6; n++)
            {
                long r1 = System.nanoTime();
                stage = n;
                long u1 = System.nanoTime();
                update_decks();
                long u2 = System.nanoTime();

                try
                {
                    long s1 = System.nanoTime();

                    double[][] odds = simulate_test(games);

                    long s2 = System.nanoTime();
                    long r2 = System.nanoTime();

                    double u_time = (u2 - u1) / 1_000_000_000.0;
                    double s_time = (s2 - s1) / 1_000_000_000.0;
                    double r_time = (r2 - r1) / 1_000_000_000.0;

                    System.out.print("\n\n\n");

                    System.out.printf(street_name[n] + " took: %.3f s\n", r_time);
                    System.out.printf("Lookup Time: %.3f s\n", u_time);
                    System.out.printf("Simulate Time: %.3f s\n", s_time);


                    System.out.print("\nCommunity Cards:  ");
                    for (int t = 0; t < n; t++)
                    {
                        int c = table[t];
                        String cr = rank_name[c/4];
                        String cs = suit_name[c%4];
                        System.out.print(cr + cs + "  ");
                    }

                    System.out.print("\n+---------+-----------+----------+--------+");
                    for (int a = 1; a < players; a++) System.out.print("--------+");
                    System.out.print("\n| Players |   Cards   |    EV    |  Wins  |");
                    for (int a = 1; a < players; a++) System.out.print(" " + (a + 1) + "-Draw |");
                    System.out.print("\n+---------+-----------+----------+--------+");
                    for (int a = 1; a < players; a++) System.out.print("--------+");
                    System.out.println();

                    //System.out.println("+---------+-----------+----------+--------+--------+--------+--------+--------+--------+--------+--------+");
                    //System.out.println("| Players |   Cards   |    EV    |  Wins  | 2-Draw | 3-Draw | 4-Draw | 5-Draw | 6-Draw | 7-Draw | 8-Draw |");
                    //System.out.println("+---------+-----------+----------+--------+--------+--------+--------+--------+--------+--------+--------+");


                    for (int p = 0; p < players; p++)
                    {
                        int c1 = player[p][0];
                        int c2 = player[p][1];

                        String cr1 = rank_name[c1/4];
                        String cs1 = suit_name[c1%4];
                        String cr2 = rank_name[c2/4];
                        String cs2 = suit_name[c2%4];
                        String pcs = cr1 + cs1 + ' ' + cr2 + cs2;

                        double EV = expected_value(odds[p], 20.0 * players, 20.0);
                        System.out.printf("|    %-3d  |   %-6s  |  %6.2f  ", (p + 1), pcs, EV);
                        System.out.printf("| %6.2f ", 100.0 * odds[p][0]);

                        for (int b = 1; b < players; b++)
                        {
                            System.out.printf("| %6.2f ", 100.0 * odds[p][b]);
                        }
                        System.out.println("|");
                    }
                    System.out.print("+---------+-----------+----------+--------+");
                    for (int a = 1; a < players; a++) System.out.print("--------+");

                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            reset();
            long t2 = System.nanoTime();

            double nums = (3.0 * players * games) / 1_000_000.0 ;
            double secs = (t2 - t1) / (1_000_000_000.0);
            double rate = nums / secs;

            System.out.print("\n\n\n");
            System.out.printf("Time: %.3f s\n", secs);
            System.out.printf("Rate: %.3f M/s\n", rate);
        }
    }


    static long[] run_batch_test(int p, long games)
    {
        int p1 = player[p][0];
        int p2 = player[p][1];

        long r_count = river_count[stage];
        long game_per_riv = games / r_count;
        int dealt = 45 - (2 * (players - 1));

        int[] deck = new int[45];
        int[] score = new int[1326];
        long[] results = new long[players];
        long pocket = (1L << p1) | (1L << p2);

        SplittableRandom rng = new SplittableRandom();

        int riv;
        long used;
        int c1, c2;
        int  villan;
        boolean lost;
        int hero, tie;

        for (int r = 0; r < r_count; r++)
        {
            riv = river[p][r];
            score = lookup[riv];
            used = pocket | cards[riv];
            hero = score[get_index(p1, p2)];

            for (int i = 0, n = 0; i < 52; i++)
            {
                if ((used & (1L << i)) == 0) deck[n++] = i;
            }

            for (long g = 0; g < game_per_riv; g++)
            {
                lost = false; tie = 0;

                for (int a = 45, b; a > dealt;)
                {
                    b = rng.nextInt(a--);
                    c1 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c1;

                    b = rng.nextInt(a--);
                    c2 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c2;

                    villan = score[get_index(c1, c2)];

                    if (villan > hero)
                    {
                        lost = true; break;
                    }
                    if (villan == hero) tie++;
                }
                if (!lost) results[tie]++;
            }
        }
        return results;
    }
    static double[][] simulate_test(long games) throws Exception
    {
        long r_count = river_count[stage];
        double simulated = games - games % r_count;

        long[][] results = new long[players][players];
        double[][] ratios = new double[players][players];

        List<Callable<long[]>> jobs = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(players);

        for (int p = 0; p < players; p++)
        {
            final int play = p;
            jobs.add(() -> run_batch_test(play, games));
        }
        List<Future<long[]>> futures = pool.invokeAll(jobs);

        for (int i = 0; i < futures.size(); i++)
        {
            results[i] = futures.get(i).get();
        }
        pool.shutdown();

        for (int i = 0; i < players; i++)
        {
            for (int j = 0; j < players; j++)
            {
                ratios[i][j] = (double) results[i][j] / simulated;
            }
        }

        return ratios;
    }
    static double expected_value(double[] odds, double pot, double you)
    {
        double lose = 1.0;
        double gain = 0.0;

        for (int n = 0; n < players; n++)
        {
            lose -= odds[n];
            gain += odds[n] * (pot / (n + 1.0) - you);
        }

        gain -= lose * you;
        return gain;
    }


    static void accuracy_profile_intervals(
            long games_max,
            int interval,
            int trials,
            String out_prefix
    ) throws Exception
    {
        Locale.setDefault(Locale.US);

        int[] test = {
                36,
                27,
                16,
                9,
                4
        };

        int[][] plays = {
                { 0,  8},
                {40, 44},
                {38, 39},
                {13, 21},
                {10, 50},
                {31, 34},
                {11, 19},
                { 5, 25},
        };

        double[] exact = {
                0.81176614,
                0.95050505,
                0.56289382
        };

        for (int i = 0; i < table.length; i++)
        {
            table[i] = test[i];
        }

        for (int i = 0; i < player.length; i++)
        {
            player[i][0] = plays[i][0];
            player[i][1] = plays[i][1];
        }

        build_maps();
        stage = 3;
        update_decks();
        load_lookup();
        stage = 4;
        update_decks();
        stage = 5;
        update_decks();

        String f0 = out_prefix + "_p0.csv";
        String f1 = out_prefix + "_p1.csv";
        String f2 = out_prefix + "_p2.csv";

        try (
                BufferedWriter w0 = new BufferedWriter(new FileWriter(f0));
                BufferedWriter w1 = new BufferedWriter(new FileWriter(f1));
                BufferedWriter w2 = new BufferedWriter(new FileWriter(f2))
        )
        {
            w0.write("trial,games,abs_error,signed_error,estimate\n");
            w1.write("trial,games,abs_error,signed_error,estimate\n");
            w2.write("trial,games,abs_error,signed_error,estimate\n");

            ExecutorService pool = Executors.newFixedThreadPool(3);

            for (int t = 0; t < trials; t++)
            {
                final int trial = t;

                List<Callable<Void>> jobs = new ArrayList<>(3);

                jobs.add(() -> { run_batch_test_profile(0, games_max, interval, exact[0], trial, w0); return null; });
                jobs.add(() -> { run_batch_test_profile(1, games_max, interval, exact[1], trial, w1); return null; });
                jobs.add(() -> { run_batch_test_profile(2, games_max, interval, exact[2], trial, w2); return null; });

                List<Future<Void>> futures = pool.invokeAll(jobs);

                for (Future<Void> f : futures) f.get();

                w0.flush();
                w1.flush();
                w2.flush();
            }

            pool.shutdown();
        }

        System.out.println("Wrote: " + f0);
        System.out.println("Wrote: " + f1);
        System.out.println("Wrote: " + f2);
    }

    static void run_batch_test_profile(
            int p,
            long games_max,
            int interval,
            double exact,
            int trial,
            BufferedWriter out
    ) throws Exception
    {
        int p1 = player[p][0];
        int p2 = player[p][1];

        long r_count = river_count[stage];
        int dealt = 45 - (2 * (players - 1));

        int[] deck = new int[45];
        long[] results = new long[players];
        long pocket = (1L << p1) | (1L << p2);

        SplittableRandom rng = new SplittableRandom();

        long used;
        int c1, c2;
        int villan;
        boolean lost;
        int hero, tie;

        long done = 0L;
        long next = Math.min((long) interval, games_max);

        for (int r = 0; r < r_count; r++)
        {
            int riv = river[p][r];
            int[] score = lookup[riv];
            used = pocket | cards[riv];
            hero = score[get_index(p1, p2)];

            for (int i = 0, n = 0; i < 52; i++)
            {
                if ((used & (1L << i)) == 0) deck[n++] = i;
            }

            long remaining = games_max - done;
            if (remaining <= 0) break;

            long target_for_river = remaining / (r_count - r);
            if (target_for_river <= 0) target_for_river = remaining;

            for (long g = 0; g < target_for_river; g++)
            {
                lost = false;
                tie = 0;

                for (int a = 45, b; a > dealt;)
                {
                    b = rng.nextInt(a--);
                    c1 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c1;

                    b = rng.nextInt(a--);
                    c2 = deck[b];
                    deck[b] = deck[a];
                    deck[a] = c2;

                    villan = score[get_index(c1, c2)];

                    if (villan > hero)
                    {
                        lost = true;
                        break;
                    }
                    if (villan == hero) tie++;
                }

                if (!lost) results[tie]++;

                done++;

                if (done == next)
                {
                    double est = (double) results[0] / (double) done;
                    double signed = est - exact;
                    double abs = Math.abs(signed);

                    out.write(trial + "," + done + "," + abs + "," + signed + "," + est + "\n");

                    if (next == games_max) break;
                    next = Math.min(next + interval, games_max);
                }
            }

            if (done >= games_max) break;
        }
    }



    static void reset()
    {
        for (int i = 0; i < 1176; i++)
        {
            cards[i] = 0L;
            index[i] = 0L;

            for (int j = 0; j < 1326; j++)
            {
                lookup[i][j] = 0;
            }
        }
        for (int i = 0; i < players; i++)
        {
            player[i][0] = 0;
            player[i][1] = 0;
            for (int j = 0; j < 1081; j++)
            {
                river[i][j] = 0;
            }
        }
    }
    static void start()
    {
        for (int i = 0; i < 52; i++)
        {
            main_deck[i] = i;
        }
    }
    static void shuffle()
    {
        int j, temp;
        for (int i = 51; i > 0; i--) {
            j = random.nextInt(i + 1);
            temp = main_deck[i];
            main_deck[i] = main_deck[j];
            main_deck[j] = temp;
        }
    }
    static void deal_cards()
    {
        int c = 0;
        for (int n = 0; n < player.length; n++)
        {
            player[n][0] = main_deck[c++];
            player[n][1] = main_deck[c++];
        }
        for (int n = 0; n < table.length; n++)
        {
            table[n] = main_deck[c++];
        }
    }


    static void load_lookup()
    {
        long size = 2L * 1326L;
        short[] buffer = new short[1326];
        MemorySegment dest = MemorySegment.ofArray(buffer);

        for (int i = 0; i < 1176; i++)
        {
            long offset = size * index[i];
            MemorySegment.copy(lookup_file, offset, dest, 0, size);

            for (int j = 0; j < 1326; j++)
            {
                lookup[i][j] = buffer[j];
            }
        }
    }
    static void build_maps()
    {
        try {
            try (FileChannel lookup_channel = FileChannel.open(file_name, StandardOpenOption.READ);)
            {
                lookup_file = lookup_channel.map(FileChannel.MapMode.READ_ONLY, 0, lookup_channel.size(), arena);
            }
        } catch (Exception e) {e.printStackTrace();}
    }
    static void update_decks()
    {
        if (stage == 3)
        {
            long used = 0L;

            int[] rivers = {
                    table[0],
                    table[1],
                    table[2],
                    0 , 0 };

            used |= 1L << table[0];
            used |= 1L << table[1];
            used |= 1L << table[2];

            for (int a = 0, n = 0; a < 52; a++)
            {
                if ((used & (1L << a)) != 0)
                    continue;
                for (int b = a + 1; b < 52; b++)
                {
                    if ((used & (1L << b)) != 0)
                        continue;

                    rivers[3] = a; rivers[4] = b;
                    index[n] = get_index(5, rivers);
                    cards[n++] = used | (1L << a) | (1L << b);
                }
            }
            for (int p = 0; p < players; p++)
            {
                long pocket = 0L; int n = 0;
                pocket |= 1L << player[p][0];
                pocket |= 1L << player[p][1];

                for (int r = 0; r < cards.length; r++)
                {
                    if ((pocket & cards[r]) == 0)
                        river[p][n++] = r;

                }
            }
            load_lookup();
        }
        else if (stage == 4)
        {
            long turn = 1L << table[3];

            for (int i = 0, n = 0; i < 1176; i++)
            {
                if ((turn & cards[i]) != 0)
                {
                    for (int j = 0; j < 1326; j++)
                    {
                        lookup[n][j] = lookup[i][j];
                    }
                    cards[n++] = cards[i];
                }
            }
            for (int p = 0; p < players; p++)
            {
                long pocket = 0L; int n = 0;
                pocket |= 1L << player[p][0];
                pocket |= 1L << player[p][1];

                for (int r = 0; r < 48; r++)
                {
                    if ((pocket & cards[r]) == 0)
                        river[p][n++] = r;
                }
            }
        }
        else if (stage == 5)
        {
            long last = 1L << table[4];

            for (int i = 0; i < 48; i++)
            {
                if ((last & cards[i]) != 0)
                {
                    for (int p = 0; p < players; p++)
                    {
                        river[p][0] = i;
                    }
                    cards[0] = cards[i];
                    break;
                }
            }
        }
    }
    static double round(double num, int n)
    {
        if (num == 0) {
            return 0;
        }

        final double d = Math.ceil(Math.log10(Math.abs(num)));
        final int power = n - (int) d;
        final double magnitude = Math.pow(10, power);
        final long shifted = Math.round(num * magnitude);
        return shifted / magnitude;
    }


    static int get_index(int a, int b)
    {
        if (a < b) {
            return (51 * a - (a * (a - 1)) / 2) + b - a - 1;
        } else {
            return (51 * b - (b * (b - 1)) / 2) + a - b - 1;
        }
    }
    static int comb(int n, int k)
    {
        switch (k) {
            case 0:  return 1;
            case 1:  return n;
            case 2:  return n*(n-1)/2;
            case 3:  return n*(n-1)*(n-2)/6;
            case 4:  return n*(n-1)*(n-2)*(n-3)/24;
            default: return n*(n-1)*(n-2)*(n-3)*(n-4)/120;
        }
    }
    static long get_index(int stage, int[] cards)
    {
        int cur = 0;
        int idx = 0;
        int pre = -1;

        int[] copy = new int[5];
        for (int i = 0; i < stage; i++)
        {
            copy[i] = cards[i];
        }
        sort(stage, copy, true);

        for (int i = 0, r, l, u; i < stage; i++)
        {
            cur = copy[i];
            r = stage - i;
            l = pre + 1;
            u = cur - 1;

            if (l <= u)
            {
                idx += comb(52 - l, r) - comb(51 - u, r);
            }
            pre = cur;
        }
        return (long) idx;
    }
    static void sort(int length, int[] arr, boolean rev)
    {
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
}