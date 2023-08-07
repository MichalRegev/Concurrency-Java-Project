package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.logging.Level;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */

public class Player implements Runnable {
    /**
     * The game environment object.
     */
    private final Env env;
    private boolean doneCheckSet=true;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;


    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;
    private Dealer dealer;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    private ArrayBlockingQueue <Integer> chooseCards;
    private ArrayBlockingQueue <Integer> actions;
    private long pen= 0;
    protected boolean waiting=false;
    private boolean endPen= true;
    private Object scoreLock = new Object();

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.chooseCards= new ArrayBlockingQueue<Integer>(3);
        this.actions= new ArrayBlockingQueue<Integer>(3);
        this.dealer=dealer;
        terminate=false;

    }

    public ArrayBlockingQueue<Integer> getChooseCards (){
        return  chooseCards;
    }
    public ArrayBlockingQueue<Integer> getActions (){
        return  actions;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();
    

            try {
                while (!terminate) {
                    boolean size3 = false;
                    try {
                        synchronized (this) {
                            while (actions.isEmpty()) {
                                wait(); //key press -> notify
                            }
                        }

                    } catch (InterruptedException ignored) {
                    }
                    dealer.cardsLock.readLock().lock();
                        if (!actions.isEmpty()) {
                            int slot = actions.remove();
                            if (!human) {
                                synchronized (aiThread) {
                                    aiThread.notifyAll();
                                }
                            }
                            if (table.slotToCard[slot] != null) { //there is a card in the slot
                                if (chooseCards.contains(table.slotToCard[slot])) { //to cancel pressed token
                                    table.removeToken(id, slot);
                                    chooseCards.remove(table.slotToCard[slot]);
                                } else { //not pressed yet
                                    if (chooseCards.size() < 3) {
                                        chooseCards.add(table.slotToCard[slot]);
                                        table.placeToken(id, slot);
                                        if (chooseCards.size() == 3) {
                                            table.getpSet().add(this);
                                            size3 = true;
                                        }
                                    }
                                }
                            }
                        }
                        dealer.cardsLock.readLock().unlock();
                    if (size3) {
                        doneCheckSet = false;
                        endPen=false;
                        dealer.wakeMe();
                        synchronized (this) {
                            while (!doneCheckSet) {
                                wait();
                            }
                        }
                        penalty();
                    }
                }
            } catch (InterruptedException ignored) {
            }


        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {

                try{  synchronized(aiThread) {

                    while ((actions.size()==3||waiting||!doneCheckSet)&&!terminate){ 
                        aiThread.wait();
                    }
                }
                }catch(InterruptedException ignored) {}

                LinkedList<Integer> noEmptySlots=new LinkedList<Integer>();
                for (int i=0; i<12;i++){
                    if(!dealer.emptySlots.contains(i)){
                        noEmptySlots.add(i);
                    }
                }
                if(noEmptySlots.size()!=0) {
                    Random rnd = new Random();
                    int slot = rnd.nextInt(noEmptySlots.size());
                    keyPressed(noEmptySlots.get(slot));
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate=true;
        playerThread.interrupt();
        try{
            playerThread.join();
        }catch (InterruptedException e){}
    }


    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (actions.size() < 3 && endPen) {
            actions.add(slot);
            synchronized (this) {
                notifyAll(); 
            }
        }

    }

    public void setPen(int penNew){
        pen=penNew;
    }

    public void setDoneCheckSet (){
        doneCheckSet=true;
        synchronized(this){
            notifyAll();
        }
               if (!human){
                if (aiThread!=null){
            synchronized (aiThread) {
                aiThread.notifyAll();
            }
            }
        }

    }



    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        synchronized (scoreLock){
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    }

    /**
     * Penalize a player and perform other related actions.
     */






    public boolean hasDeclareSet () {
        if (getChooseCards().size() == 3) {
            int card1 = chooseCards.remove();
            int card2 = chooseCards.remove();
            int card3 = chooseCards.remove();
            int[] cardsSet = {card1, card2, card3};
            if (env.util.testSet(cardsSet)) {
                point();
                pen = env.config.pointFreezeMillis;
                table.removeToken(id, table.cardToSlot[card1]);
                table.removeToken(id, table.cardToSlot[card2]);
                table.removeToken(id, table.cardToSlot[card3]);
                dealer.getShouldBeRemoved().addFirst(table.cardToSlot[card1]);
                dealer.getShouldBeRemoved().addFirst(table.cardToSlot[card2]);
                dealer.getShouldBeRemoved().addFirst(table.cardToSlot[card3]);
                dealer.removeCardsFromTable();
                return true;
            } else {
                getChooseCards().add(card1);
                getChooseCards().add(card2);
                getChooseCards().add(card3);
                pen = env.config.penaltyFreezeMillis;
                return false;
            }
        }

        return false;
    }

    public void removeDeletedCard (int card){
        if (chooseCards.remove(card)) {
            table.removeToken(id,table.cardToSlot[card]);
            table.getpSet().remove(this);
            actions.remove(table.cardToSlot[card]);
            if(!human) {
                synchronized (aiThread) {
                    aiThread.notifyAll();
                }
            }
            setDoneCheckSet();

        }
    }

    public int score() {
        synchronized (scoreLock){
        return score;
        }
    }

    public void penalty() {
        try {
            env.ui.setFreeze(id, pen);
            long counter = pen / 1000;
            while (counter != 0) {
                synchronized (this) {
                    wait(1000);
                }
                pen = pen - 1000;
                env.ui.setFreeze(id, pen);
                counter--;
            }
            endPen=true;
        }catch (InterruptedException ignored){}
    }

    public void sendToWait(){
            waiting=true;
    }

    public void wakeUp(){
        actions.clear();
        waiting = false;
        if(!human) {
            synchronized (aiThread){
                aiThread.notifyAll();
            }
        }

    }





}
