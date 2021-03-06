package org.asdfjkl.bookmaker;

import org.apache.commons.cli.*;
import org.asdfjkl.bookmaker.lib.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public class Main {

    public static void main(String[] args) {

        Options options = new Options();

        Option input = new Option("i", "input", true, "input PGN file");
        input.setRequired(true);
        options.addOption(input);

        Option output = new Option("o", "output", true, "output book file");
        output.setRequired(true);
        options.addOption(output);

        Option depth = new Option("d", "depth", true, "maximal depth in halfmoves");
        depth.setType(Number.class);
        options.addOption(depth);

        Option limit = new Option("e", "eco-depth", true, "adapt depth w.r.t. ECO");
        limit.setType(Number.class);
        options.addOption(limit);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        Eco ecolist = new Eco();
        ecolist.createEco();

        try {
            cmd = parser.parse(options, args);
            String inputFilePath = cmd.getOptionValue("input");
            String outputFilePath = cmd.getOptionValue("output");

            int depthValue = 40;
            if (cmd.hasOption("depth")) {
                depthValue = ((Number)cmd.getParsedOptionValue("depth")).intValue();
                if(depthValue > 40 || depthValue < 1) {
                    System.out.println("supplied depth value "+depthValue+ " out of range 1<>40. Setting to 40 (default)");
                }
            }
            System.out.println("using depth up to "+depthValue + " halfmoves");

            boolean adaptiveDepth = false;
            int addDepth = 10;
            if (cmd.hasOption("eco-depth")) {
                adaptiveDepth = true;
                addDepth = ((Number)cmd.getParsedOptionValue("eco-depth")).intValue();
                System.out.println("using adaptive depth w.r.t. ECO: min(last ECO classified position + " + addDepth + " halfmoves, depth)");
            }
            else {
                System.out.println("using fixed halfmove depth");
            }

            /*
            int limitValue = 0;
            if (cmd.hasOption("limit")) {
                limitValue = ((Number)cmd.getParsedOptionValue("limit")).intValue();
                if(limitValue > 5 || limitValue < 1) {
                    System.out.println("supplied limit value "+limitValue+ " out of range 1<>5. Including all positions");
                }
            }
            if(limitValue == 0) {
                System.out.println("including all positions...");
            } else {
                System.out.println("limit to positions that accour strictly more than "+limitValue);
            }*/

            //System.out.println(inputFilePath);
            //System.out.println(outputFilePath);

            //System.out.println("TEST: reading all games from middleg.pgn");
            //String middleg = "C:/Users/user/MyFiles/workspace/test_databases/middleg.pgn";


            OptimizedRandomAccessFile raf = null;
            PgnReader reader = new PgnReader();
            if(reader.isIsoLatin1(inputFilePath)) {
                reader.setEncodingIsoLatin1();
            }
            System.out.println("scanning PGN...");
            ArrayList<Long> offsets = reader.scanPgn(inputFilePath);

            HashMap<Long, ArrayList<BookEntry>> bookMap = new HashMap<Long, ArrayList<BookEntry>>();

            try {
                raf = new OptimizedRandomAccessFile(inputFilePath, "r");
                for (int i=0;i<offsets.size();i++) {
                    long offset_i = offsets.get(i);
                    if(i % 5000 == 0) {
                        printProgress(i, offsets.size(), "reading games...");
                    }
                    raf.seek(offset_i);
                    Game g = reader.readGame(raf);

                    String result = g.getHeader("Result");
                    int win = 0;
                    int draw = 0;
                    int loss = 0;
                    if(!result.isEmpty()) {
                        if(result.equals("1-0")) {
                            win+=1;
                        }
                        if(result.equals("1/2-1/2")) {
                            draw+=1;
                        }
                        if(result.equals("0-1")) {
                            loss+=1;
                        }
                    }
                    int w_elo = 0;
                    int b_elo = 0;
                    String sWhiteElo = g.getHeader("WhiteElo");
                    String sBlackElo = g.getHeader("BlackElo");
                    if(!sWhiteElo.isEmpty()) {
                        try {
                            w_elo = Integer.parseInt(sWhiteElo);
                        } catch (NumberFormatException e) {
                            w_elo = 0;
                        }
                    }
                    if(!sBlackElo.isEmpty()) {
                        try {
                            b_elo = Integer.parseInt(sWhiteElo);
                        } catch (NumberFormatException e) {
                            b_elo = 0;
                        }
                    }
                    int adaptiveDepthValue = 0;
                    if(adaptiveDepth) {
                        GameNode temp = g.getRootNode();
                        for (int j = 0; j < depthValue; j++) {
                            if (ecolist.eco.containsKey(temp.getBoard().getZobrist())) {
                                adaptiveDepthValue = j;
                            }
                            if (!temp.hasChild()) {
                                break;
                            } else {
                                temp = temp.getVariation(0);
                            }
                        }
                    }
                    // take the minimum of all halfmoves up to a certain depth
                    // and the last seen ECO position + 10 halfmoves

                    adaptiveDepthValue = Math.min(adaptiveDepthValue + addDepth, depthValue);
                    GameNode node = g.getRootNode();
                    for(int j=0;j<adaptiveDepthValue;j++) {
                        if(!node.hasChild()) {
                            break;
                        } else {
                            GameNode child = node.getVariation(0);
                            String uci = child.getMove().getUci();
                            boolean isCastle = false;
                            if(uci.equals("e1g1") || uci.equals("e1c1")) {
                                if(node.getBoard().getPieceAt(4,0) == CONSTANTS.WHITE_KING) {
                                    isCastle = true;
                                }
                            }
                            if(uci.equals("e8g8") || uci.equals("e8c8")) {
                                if(node.getBoard().getPieceAt(4,7) == CONSTANTS.BLACK_KING) {
                                    isCastle = true;
                                }
                            }
                            int mPoly = uciToPoly(uci, isCastle);
                            //System.out.println(uci);
                            //System.out.println(mPoly);
                            int addElo = -1;
                            if(mPoly >= 0) {
                                if(w_elo > 0 && node.getBoard().turn == CONSTANTS.WHITE) {
                                    addElo = w_elo;
                                }
                                if(b_elo > 0 && node.getBoard().turn == CONSTANTS.BLACK) {
                                    addElo = b_elo;
                                }
                            }
                            long zobrist = node.getBoard().getZobrist();
                            boolean createNewEntry = false;
                            boolean addNewEntry = false;
                            if(bookMap.containsKey(zobrist)) {
                                ArrayList<BookEntry> entries = bookMap.get(zobrist);
                                int idx = -1;
                                for(int k=0; k < entries.size();k++) {
                                    if(entries.get(k).move == mPoly) {
                                        idx = k;
                                    }
                                }
                                // entry with move exists
                                if(idx >=0) {
                                    if(addElo > 0) {
                                        //System.out.println("idx >0, addElo >0");
                                        BookEntry e = entries.get(idx);
                                        e.posCount += 1;
                                        e.whiteWinCount += win;
                                        e.drawCount += draw;
                                        e.whiteLossCount += loss;
                                        e.eloSum += addElo;
                                        e.eloCount += 1;
                                        entries.set(idx, e);
                                        bookMap.put(zobrist, entries);
                                    } else {
                                        //System.out.println("idx >0, addElo <0");
                                        BookEntry e = entries.get(idx);
                                        e.posCount += 1;
                                        e.whiteWinCount += win;
                                        e.drawCount += draw;
                                        e.whiteLossCount += loss;
                                        entries.set(idx, e);
                                        //System.out.println(entries.size());
                                        bookMap.put(zobrist, entries);
                                    }
                                } else {
                                    if(addElo > 0) {
                                        //System.out.println("idx >0, addElo >0");
                                        BookEntry e = new BookEntry();
                                        e.move = mPoly;
                                        e.posCount = 1;
                                        e.whiteWinCount = win;
                                        e.drawCount = draw;
                                        e.whiteLossCount = loss;
                                        e.eloSum = addElo;
                                        e.eloCount = 1;
                                        entries.add(e);
                                        bookMap.put(zobrist, entries);
                                    } else {
                                        //System.out.println("idx >0, addElo <0");
                                        BookEntry e = new BookEntry();
                                        e.move = mPoly;
                                        e.posCount += 1;
                                        e.whiteWinCount += win;
                                        e.drawCount += draw;
                                        e.whiteLossCount += loss;
                                        e.eloSum = 0;
                                        e.eloCount = 0;
                                        entries.add(e);
                                        //System.out.println(entries.size());
                                        bookMap.put(zobrist, entries);
                                    }

                                }
                            } else {
                                if(addElo > 0) {
                                    BookEntry e = new BookEntry();
                                    e.move = mPoly;
                                    e.posCount = 1;
                                    e.whiteWinCount = win;
                                    e.drawCount = draw;
                                    e.whiteLossCount = loss;
                                    e.eloSum = addElo;
                                    e.eloCount = 1;
                                    ArrayList<BookEntry> entries = new ArrayList<>();
                                    entries.add(e);
                                    bookMap.put(zobrist, entries);
                                } else {
                                    BookEntry e = new BookEntry();
                                    e.move = mPoly;
                                    e.posCount = 1;
                                    e.whiteWinCount = win;
                                    e.drawCount = draw;
                                    e.whiteLossCount = loss;
                                    e.eloSum = 0;
                                    e.eloCount = 0;
                                    ArrayList<BookEntry> entries = new ArrayList<>();
                                    entries.add(e);
                                    bookMap.put(zobrist, entries);
                                }
                            }
                            node = child;
                        }
                    }
                }
                printProgress(offsets.size(), offsets.size(), "reading games...");
                System.out.println("\ncopying hashmap to array");
                // convert hashmap
                ArrayList<Tuple<Long, BookEntry>> arrBook = new ArrayList<>();

                for (Long key : bookMap.keySet()) {
                    ArrayList<BookEntry> entries = bookMap.get(key);
                    //System.out.println("entries: "+entries.size());
                    for(int i=0;i<entries.size();i++) {
                        arrBook.add(new Tuple<>(key, entries.get(i)));
                    }
                }
                System.out.println("sorting book by zobrist key");
                // sort arrBook by zobrist
                Collections.sort(arrBook, new Comparator<Tuple<Long, BookEntry>>() {
                    @Override
                    public int compare(Tuple<Long, BookEntry> tuple1, Tuple<Long, BookEntry> tuple2)
                    {
                        return  Long.compareUnsigned(tuple1.x, tuple2.x);
                    }
                });

                // write everything to file
                try {
                    OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFilePath));
                    for(int i=0;i<arrBook.size();i++) {
                        if(i % 5000 == 0) {
                            printProgress(i, arrBook.size(), "writing book...");
                        }

                        Tuple<Long, BookEntry> entry = arrBook.get(i);

                        ByteBuffer bbZobrist = ByteBuffer.allocate(8);
                        bbZobrist.putLong(entry.x);
                        byte[] bZobrist = bbZobrist.array();
                        outputStream.write(bZobrist);

                        //ByteBuffer bbMove = ByteBuffer.allocate(2);
                        //System.out.println("entry move: "+entry.y.move);
                        //bbMove.putInt(entry.y.move);
                        byte[] bMove = putInt16(entry.y.move); //bbMove.array();
                        outputStream.write(bMove);

                        ByteBuffer bbPosCount = ByteBuffer.allocate(4);
                        bbPosCount.putInt(entry.y.posCount);
                        byte[] bPosCount = bbPosCount.array();
                        outputStream.write(bPosCount);

                        long countRated = entry.y.whiteWinCount;
                        countRated += entry.y.drawCount;
                        countRated += entry.y.whiteLossCount;

                        /*
                        System.out.println("--------------------");
                        System.out.println("pos count :" + entry.y.posCount);
                        System.out.println("white wins: "+entry.y.whiteWinCount);
                        System.out.println("black wins: "+entry.y.whiteLossCount);
                        System.out.println("draws     : "+entry.y.drawCount);
                        */

                        int winperc = 0;
                        int drawperc = 0;
                        int lossperc = 0;
                        if(countRated > 0) {
                            winperc = (int) ((entry.y.whiteWinCount * 1.0 / countRated) * 100);
                            drawperc = (int) ((entry.y.drawCount * 1.0 / countRated) * 100);
                            lossperc = (int) ((entry.y.whiteLossCount * 1.0 / countRated) * 100);
                        }

                        outputStream.write((Integer.valueOf(winperc)).byteValue());
                        outputStream.write((Integer.valueOf(drawperc)).byteValue());
                        outputStream.write((Integer.valueOf(lossperc)).byteValue());

                        int avgElo = 0;
                        if(entry.y.eloCount > 0) {
                            avgElo = (int) (entry.y.eloSum / (entry.y.eloCount * 1.0));
                        }
                        byte[] bElo = putInt16(avgElo);
                        outputStream.write(bElo);
                    }
                    printProgress(arrBook.size(), arrBook.size(), "writing book...");

                } catch (FileNotFoundException e) {

                } catch (IOException e) {

                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            /*
            System.out.println("\ncopying hashmap to array");
            // convert hashmap
            ArrayList<Tuple<Long, BookEntry>> arrBook = new ArrayList<>();

            for (Long key : bookMap.keySet()) {
                ArrayList<BookEntry> entries = bookMap.get(key);
                for(int i=0;i<entries.size();i++) {
                    arrBook.add(new Tuple<>(key, entries.get(i)));
                }
            }
            System.out.println("book size before sorting: "+arrBook.size());

            System.out.println("sorting book by zobrist key\n");
            // sort arrBook by zobrist
            Collections.sort(arrBook, new Comparator<Tuple<Long, BookEntry>>() {
                @Override
                public int compare(Tuple<Long, BookEntry> tuple1, Tuple<Long, BookEntry> tuple2)
                {
                    return  tuple1.x.compareTo(tuple2.x);
                }
            });

            System.out.println("book size after sorting: "+arrBook.size());

            // write everything to file
            try {
                OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFilePath));
                for(int i=0;i<arrBook.size();i++) {
                    if(i % 5000 == 0) {
                        printProgress(i, arrBook.size(), "writing book...");
                    }

                    Tuple<Long, BookEntry> entry = arrBook.get(i);

                    ByteBuffer bbZobrist = ByteBuffer.allocate(8);
                    bbZobrist.putLong(entry.x);
                    byte[] bZobrist = bbZobrist.array();
                    outputStream.write(bZobrist);

                    ByteBuffer bbMove = ByteBuffer.allocate(2);
                    bbMove.putInt(entry.y.move);
                    byte[] bMove = bbMove.array();
                    outputStream.write(bMove);

                    ByteBuffer bbPosCount = ByteBuffer.allocate(4);
                    bbPosCount.putInt(entry.y.posCount);
                    byte[] bPosCount = bbPosCount.array();
                    outputStream.write(bPosCount);

                    long countRated = entry.y.whiteWinCount;
                    countRated += entry.y.drawCount;
                    countRated += entry.y.whiteLossCount;

                    int winperc = (int) (entry.y.whiteWinCount / countRated);
                    int drawperc = (int) (entry.y.drawCount / countRated);
                    int lossperc = (int) (entry.y.whiteLossCount / countRated);

                    ByteBuffer bbWinPerc = ByteBuffer.allocate(1);
                    bbWinPerc.putInt(winperc);
                    byte[] bWinPerc = bbWinPerc.array();
                    outputStream.write(bWinPerc);

                    ByteBuffer bbDrawPerc = ByteBuffer.allocate(1);
                    bbDrawPerc.putInt(drawperc);
                    byte[] bDrawPerc = bbDrawPerc.array();
                    outputStream.write(bDrawPerc);

                    ByteBuffer bbLossPerc = ByteBuffer.allocate(1);
                    bbLossPerc.putInt(lossperc);
                    byte[] bLossPerc = bbLossPerc.array();
                    outputStream.write(bLossPerc);

                    long avgElo = entry.y.eloSum / entry.y.eloCount;
                    ByteBuffer bbElo = ByteBuffer.allocate(2);
                    bbElo.putLong(avgElo);
                    byte[] bElo = bbElo.array();
                    outputStream.write(bElo);
                }

            } catch (FileNotFoundException e) {

            } catch (IOException e) {

            }

             */
            System.out.println("");
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);

            System.exit(1);
        }
    }

    public static void printProgress(int current, int finish, String message) {

        System.out.print("\r" + message + ": "+ current + "/"+finish);

    }

    public static byte[] putInt16(int val) {
        byte[] data = new byte[2];
        data[1] = (byte) (val & 0xFF);
        data[0] = (byte) ((val >> 8) & 0xFF);
        return data;
    }


    public static int uciToPoly(String uci, boolean isCastle) {

        if(isCastle) {

            if (uci.equals("e1g1")) {
                uci = "e1h1";
            }
            if (uci.equals("e1c1")) {
                uci = "e1a1";
            }
            if (uci.equals("e8g8")) {
                uci = "e8h8";
            }
            if (uci.equals("e8c8")) {
                uci = "e8a8";
            }
        }

        int fFile = uci.charAt(0);
        int fRow = uci.charAt(1);
        int tFile = uci.charAt(2);
        int tRow = uci.charAt(3);

        int entry = 0;
        entry += tFile - 97;
        entry += (tRow - 49) << 3;
        entry += (fFile -97) << 6;
        entry += (fRow - 49) << 9;

        if(uci.length() == 5) {
            if(uci.charAt(4) == 'n' ) {
                entry += (1 << 12);
            }
            if(uci.charAt(4) == 'b' ) {
                entry += (2 << 12);
            }
            if(uci.charAt(4) == 'r' ) {
                entry += (3 << 12);
            }
            if(uci.charAt(4) == 'q' ) {
                entry += (4 << 12);
            }
        }
        return entry;
    }



}
