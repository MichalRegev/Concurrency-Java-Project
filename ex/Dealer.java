package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;


    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;
    private  LinkedList<Integer> shouldBeRemoved;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;
    public LinkedList<Integer> emptySlots=new LinkedList<Integer>();
    public  ReadWriteLock cardsLock =new ReentrantReadWriteLock();

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime =Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        shouldBeRemoved=new LinkedList<Integer>();
        terminate=false;
        for (int i=0;i<12;i++){
            emptySlots.add(i);
        }
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        for(int i=0;i<players.length;i++){
            table.getpThread()[i]=new Thread(players[i]);
            table.getpThread()[i].start();
        }
        while (!shouldFinish()) {
            removeAllCardsFromTable(true);
            timerLoop();
        }
        removeAllCardsFromTable(false);
        updateTimerDisplay(true);
        announceWinners();
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }



    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis +999;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        for (int p= players.length-1; p>=0;p--) {
            players[p].terminate();
        }
        terminate=true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    public void removeCardsFromTable() {
        cardsLock.writeLock().lock();
            while (shouldBeRemoved.size() != 0) {
                int currSlot = shouldBeRemoved.removeFirst();
                emptySlots.add(currSlot);
                for (int i = 0; i < players.length; i++) {
                    Player toRemoveHisCard = players[i];
                    toRemoveHisCard.removeDeletedCard(table.slotToCard[currSlot]);
                }
                table.removeCard(currSlot);
            }
            if (shouldFinish()){
                terminate=true;
            }
            placeCardsOnTable();
        cardsLock.writeLock().unlock();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    public void placeCardsOnTable() {
        while (emptySlots.size() != 0) {
            Random rnd = new Random();
            int index2 = rnd.nextInt(emptySlots.size());
            int i = emptySlots.remove(index2);
            if (table.slotToCard[i] == null) {
                if (!deck.isEmpty()) {
                    int index1=rnd.nextInt(deck.size());
                    table.placeCard(deck.get(index1), i);
                    deck.remove(deck.get(index1));
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{synchronized (this) {
            wait(10);
        }}
        catch(InterruptedException e){
        }
        if (!table.getpSet().isEmpty())
            setHasDeclared();
    }

    public void wakeMe(){
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis+999, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis+999;
        }
        else
            env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(),false);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private  void removeAllCardsFromTable(boolean sholdPlace) {
        cardsLock.writeLock().lock();
            for (int i = 0; i < 12; i++) {
                if (table.slotToCard[i] != null) {
                    emptySlots.add(i);
                    int tmpCard = table.slotToCard[i];
                    deck.add(table.slotToCard[i]);
                    for (int j = 0; j < players.length; j++) {
                        if (players[j].getChooseCards().contains(tmpCard)) {
                            table.removeToken(j, i);
                            players[j].getChooseCards().remove(tmpCard);
                            players[j].getActions().remove(tmpCard);
                        }
                    }
                    table.removeCard(i);
                }
            }
            table.getpSet().clear();
        for (int j = 0; j < players.length; j++) {
            players[j].setDoneCheckSet();
        }
            if (sholdPlace) {
                placeCardsOnTable();
            }
            cardsLock.writeLock().unlock();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore=0;
        LinkedList<Integer> winners= new LinkedList<Integer>();
        for (int p=0; p<players.length;p++){
            Player player = players[p];
            if (player.score()==maxScore) {
                winners.add(player.id);
            }
            if (player.score()>maxScore) {
                maxScore=player.score();
                winners=new LinkedList<Integer>();
                winners.add(player.id);
            }
        }
        int [] finalWinners = new int [winners.size()];
        for (int w=0; w< winners.size();w++) {
            finalWinners[w]=winners.get(w);
        }
        env.ui.announceWinner(finalWinners);
    }


    private void setHasDeclared () {
        ArrayBlockingQueue<Player> pSet = table.getpSet();
        while (!pSet.isEmpty()) {
            boolean set=false;
            Player playerDeclareSet = (Player)pSet.remove();
            if (playerDeclareSet.getChooseCards().size() == 3) {
                set= playerDeclareSet.hasDeclareSet();
            }
            if (set)
               updateTimerDisplay(true);
            playerDeclareSet.setDoneCheckSet();
        }
    }

    public void allPlayersWait(){
        for (int i=0; i<players.length; i++) {
            players[i].sendToWait();
        }
    }

    public void allPlayersWakeUp(){
        for (int i=0; i<players.length; i++) {
            players[i].wakeUp();
        }
    }


    public LinkedList<Integer>  getShouldBeRemoved (){
        return shouldBeRemoved;
    }
}
