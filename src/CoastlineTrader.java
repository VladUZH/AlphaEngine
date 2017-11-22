import java.util.LinkedList;

public class CoastlineTrader {

    private Runner[] runners;
    private LimitOrder buyLimitOrder;
    private LimitOrder sellLimitOrder;
    private float originalUnitSize; // size of the initial traded volume
    private float uniteSizeFromInventory; // inventory dictates the current unit size, this one
    private boolean initialized;
    private LocalLiquidity localLiquidityIndicator;
    private float inventory;
    private int longShort; // +1 for only Long, -1 for only Short
    private LinkedList<LimitOrder> disbalancedOrders; // list of all filled limit orders which have not been balanced by
    // an opposite order.
    private double realizedProfit; // is the total profit of all closed positions
    private double positionRealizedProfit; // is the total profit of all de-cascading orders
    private double originalDelta;

    /**
     * @param originalDelta used to define H1 and H1 of the liquidity indicator.
     * @param longShort  +1 if want only Long trades, -1 only Short.
     */
    public CoastlineTrader(double originalDelta, int longShort){
        this.originalDelta = originalDelta;
        this.originalUnitSize = 1; // computed using knowledge about the smallest volume (unite * 0.25 * 0.1). Be careful!
        this.longShort = longShort;
        initiateRunners(originalDelta);
        buyLimitOrder = null;
        sellLimitOrder = null;
        initialized = false;
        inventory = 0;
        localLiquidityIndicator = new LocalLiquidity(originalDelta, originalDelta * 2.525729, 50.0);
        disbalancedOrders = new LinkedList<>();
        realizedProfit = 0.0;
        positionRealizedProfit = 0.0;
    }


    private void initiateRunners(double originalDelta){
        runners = new Runner[3];
        runners[0] = new Runner(originalDelta, originalDelta, originalDelta, originalDelta);
        if (longShort == 1){
            runners[1] = new Runner(0.75 * originalDelta, 1.50 * originalDelta, 0.75 * originalDelta, 0.75 * originalDelta);
            runners[2] = new Runner(0.50 * originalDelta, 2.00 * originalDelta, 0.50 * originalDelta, 0.50 * originalDelta);
        } else {
            runners[1] = new Runner(1.50 * originalDelta, 0.75 * originalDelta, 0.75 * originalDelta, 0.75 * originalDelta);
            runners[2] = new Runner(2.00 * originalDelta, 0.50 * originalDelta, 0.50 * originalDelta, 0.50 * originalDelta);
        }

    }


    public void run(Price price){
        localLiquidityIndicator.computation(price);
        int[] events = new int[3];
        events[0] = runners[0].run(price);
        events[1] = runners[1].run(price);
        events[2] = runners[2].run(price);

        if (!initialized){
            initialized = true;
            correctThresholdsAndVolumes(inventory);
            putOrders(price);
        } else {
            if (checkBuyFilled(price)){
                makeBuyFilled(price);
                cancelSellLimitOrder();
            } else if (checkSellFilled(price)){
                makeSellFilled(price);
                cancelBuyLimitOrder();
            }
            if (events[findProperRunnerIndex()] != 0){ // if an event happened, but we have not a limit order at
                // that level, than we should replace all active limit orders. I. e., should use the putOrders
                // method. And it does not matter what kind of event just happened.
                cancelBuyLimitOrder();
                cancelSellLimitOrder();
                putOrders(price);
            } else {
                if (positionCrossedTargetPnL(price)){
                    closePosition(price);
                    putOrders(price);
                } else {
                    correctOrdersLevel();
                }
            }
        }
    }


    private boolean checkBuyFilled(Price price){
        if (buyLimitOrder != null){
            if (price.getAsk() < buyLimitOrder.getLevel()){
                return true;
            }
        }
        return false;
    }


    private boolean checkSellFilled(Price price){
        if (sellLimitOrder != null){
            if (price.getBid() > sellLimitOrder.getLevel()){
                return true;
            }
        }
        return false;
    }


    /**
     * Once called the method will put two orders to the order book: limit order sell at the deltaUp from the current
     * price and limit orders buy at the deltaDown form the actual price.
     * Prior to putting the limit orders, the method updates init size and thresholds.
     * @param price is the current price
     */
    private void putOrders(Price price){

        float cascadeVol = (float) (uniteSizeFromInventory * computeLiqUniteCoef(localLiquidityIndicator.liq));
        int properIndex = findProperRunnerIndex();

        if (longShort == 1){
            if (disbalancedOrders.size() == 0){ // if there is no disbalanced orders then we just open a new position.
                sellLimitOrder = null;
                if (runners[properIndex].getMode() == -1){ // expect upward DC
                    buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedOsLevel(), cascadeVol, 2, runners[properIndex].getdStarDown());
                } else { // expect downward DC
                    buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedDcLevel(), cascadeVol, 1, runners[properIndex].getDeltaDown());
                }
                computeTargetAbsPnL(buyLimitOrder);
            } else {
                if (runners[properIndex].getMode() == -1){ // expect upward DC
                    buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedOsLevel(), cascadeVol, 2, runners[properIndex].getdStarDown());
                    float decascadeVolume = 0;
                    LinkedList<LimitOrder> compensatedOrders = new LinkedList<>();
                    for (LimitOrder disbalancedOrder : disbalancedOrders){
                        if (runners[properIndex].getExpectedDcLevel() - disbalancedOrder.getLevel() > originalDelta * disbalancedOrder.getLevel()){
                            decascadeVolume += disbalancedOrder.getVolume();
                            compensatedOrders.add(disbalancedOrder);
                        }
                    }
                    if (decascadeVolume > 0){
                        sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedDcLevel(), decascadeVolume, 1, runners[properIndex].getDeltaUp());
                        for (LimitOrder compensatedOrder : compensatedOrders){
                            sellLimitOrder.addCompenstedOrder(compensatedOrder);
                        }
                    } else {
                        sellLimitOrder = null;
                    }
                } else { // expect downward DC
                    buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedDcLevel(), cascadeVol, 1, runners[properIndex].getDeltaDown());
                    float decascadeVolume = 0;
                    LinkedList<LimitOrder> compensatedOrders = new LinkedList<>();
                    for (LimitOrder disbalancedOrder : disbalancedOrders){
                        if (runners[properIndex].getExpectedOsLevel() - disbalancedOrder.getLevel() > originalDelta * disbalancedOrder.getLevel()){
                            decascadeVolume += disbalancedOrder.getVolume();
                            compensatedOrders.add(disbalancedOrder);
                        }
                    }
                    if (decascadeVolume > 0){
                        sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedOsLevel(), decascadeVolume, 2, runners[properIndex].getdStarUp());
                        for (LimitOrder compensatedOrder : compensatedOrders){
                            sellLimitOrder.addCompenstedOrder(compensatedOrder);
                        }
                    } else {
                        sellLimitOrder = null;
                    }
                }
            }
        }

        else { // only short position, so SELL first
            if (disbalancedOrders.size() == 0){ // if there is no disbalanced orders if there is no disbalanced orders then we just open a new position.
                buyLimitOrder = null;
                if (runners[properIndex].getMode() == -1){ // expect upward DC
                    sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedDcLevel(), cascadeVol, 1, runners[properIndex].getDeltaUp());
                } else { // expect downward DC
                    sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedOsLevel(), cascadeVol, 2, runners[properIndex].getdStarUp());
                }
                computeTargetAbsPnL(sellLimitOrder);
            } else {
                if (runners[properIndex].getMode() == -1){ // expect upward DC
                    float decascadeVolume = 0;
                    LinkedList<LimitOrder> compensatedOrders = new LinkedList<>();
                    for (LimitOrder disbalancedOrder : disbalancedOrders){
                        if (disbalancedOrder.getLevel() - runners[properIndex].getExpectedOsLevel() > originalDelta * disbalancedOrder.getLevel()){
                            decascadeVolume += disbalancedOrder.getVolume();
                            compensatedOrders.add(disbalancedOrder);
                        }
                    }
                    if (decascadeVolume > 0){
                        buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedOsLevel(), decascadeVolume, 2, runners[properIndex].getdStarDown());
                        for (LimitOrder compensatedOrder : compensatedOrders){
                            buyLimitOrder.addCompenstedOrder(compensatedOrder);
                        }
                    } else {
                        buyLimitOrder = null;
                    }
                    sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedDcLevel(), cascadeVol, 1, runners[properIndex].getDeltaUp());
                } else { // expect downward DC
                    float decascadeVolume = 0;
                    LinkedList<LimitOrder> compensatedOrders = new LinkedList<>();
                    for (LimitOrder disbalancedOrder : disbalancedOrders){
                        if (disbalancedOrder.getLevel() - runners[properIndex].getExpectedDcLevel() > originalDelta * disbalancedOrder.getLevel()){
                            decascadeVolume += disbalancedOrder.getVolume();
                            compensatedOrders.add(disbalancedOrder);
                        }
                    }
                    if (decascadeVolume > 0){
                        buyLimitOrder = new LimitOrder(1, price.clone(), runners[properIndex].getExpectedDcLevel(), decascadeVolume, 1, runners[properIndex].getDeltaDown());
                        for (LimitOrder compensatedOrder : compensatedOrders){
                            buyLimitOrder.addCompenstedOrder(compensatedOrder);
                        }
                    } else {
                        buyLimitOrder = null;
                    }
                    sellLimitOrder = new LimitOrder(-1, price.clone(), runners[properIndex].getExpectedOsLevel(), cascadeVol, 2, runners[properIndex].getdStarUp());
                }
            }
        }

    }


    /**
     * The method finds the index of a runner which should be used at the current inventory.
     * @return runner
     */
    private int findProperRunnerIndex(){
        if (Math.abs(inventory) < 15){
            return 0;
        } else if (Math.abs(inventory) >= 15 && Math.abs(inventory) < 30){
            return 1;
        } else {
            return 2;
        }
    }


    /**
     * Should be called when we consider the buy limit order filled
     * @param price is the current market price
     */
    private void makeBuyFilled(Price price){
        inventory += buyLimitOrder.getVolume();
        correctThresholdsAndVolumes(inventory);
        if (longShort == 1){
            disbalancedOrders.add(buyLimitOrder.clone());
        } else {
            for (LimitOrder compensatedOrder : buyLimitOrder.compensatedOrders){
                double priceMove = (buyLimitOrder.getLevel() - compensatedOrder.getLevel()) * longShort;
                if (priceMove < 0){
                    System.out.println("Negative price move when Buy? " + priceMove);
                }
                positionRealizedProfit += priceMove * buyLimitOrder.getVolume();
                disbalancedOrders.remove(compensatedOrder);
            }
            buyLimitOrder.cleanCompensatedList();
        }
        if (disbalancedOrders.size() == 0){
            closePosition(price);
        }
        buyLimitOrder = null;
    }


    /**
     * Should be called when we consider the sell limit order filled
     * @param price is the current market price
     */
    private void makeSellFilled(Price price){
        inventory -= sellLimitOrder.getVolume();
        correctThresholdsAndVolumes(inventory);
        if (longShort == -1) {
            disbalancedOrders.add(sellLimitOrder.clone());
        } else {
            for (LimitOrder compensatedOrder : sellLimitOrder.compensatedOrders){
                double priceMove = (sellLimitOrder.getLevel() - compensatedOrder.getLevel()) * longShort;
                if (priceMove < 0){
                    System.out.println("Negative price move when Sell? " + priceMove);
                }
                positionRealizedProfit += priceMove * sellLimitOrder.getVolume();
                disbalancedOrders.remove(compensatedOrder);
            }
            sellLimitOrder.cleanCompensatedList();
        }
        if (disbalancedOrders.size() == 0){
            closePosition(price);
        }
        sellLimitOrder = null;
    }



    /**
     * The method moves the DC limit order if price goes further then size of the threshold
     * @return true is something was corrected
     */
    private void correctOrdersLevel(){
        if (buyLimitOrder != null){
            correctBuyLimitOrder();
        }
        if (sellLimitOrder != null){
            correctSellLimitOrder();
        }
    }


    private boolean correctBuyLimitOrder(){
        if (buyLimitOrder.getDcORos() == 1){
            if (longShort == 1 || disbalancedOrders.size() > 1){
                int properIndex = findProperRunnerIndex();
                if (runners[properIndex].getExpectedDcLevel() > buyLimitOrder.getLevel()) {
                    if (disbalancedOrders.size() > 1 && longShort == -1){
                        if (disbalancedOrders.getLast().getLevel() - runners[properIndex].getExpectedDcLevel() > originalDelta * runners[properIndex].getExpectedDcLevel()) { // TODO: to try >= deltaUp
                            buyLimitOrder.setLevel(runners[properIndex].getExpectedDcLevel());
                        } else {
                            cancelBuyLimitOrder();
                        }
                    } else {
                        buyLimitOrder.setLevel(runners[properIndex].getExpectedDcLevel());
                    }
                    return true;
                }
            }
        }
        return false;
    }


    private boolean correctSellLimitOrder(){
        if (sellLimitOrder.getDcORos() == 1){
            if (longShort == -1 || disbalancedOrders.size() > 1){
                int properIndex = findProperRunnerIndex();
                if (runners[properIndex].getExpectedDcLevel() < sellLimitOrder.getLevel()){
                    if (disbalancedOrders.size() > 1 && longShort == 1){
                        if (runners[properIndex].getExpectedDcLevel() - disbalancedOrders.getLast().getLevel() > originalDelta * runners[properIndex].getExpectedDcLevel()) {
                            sellLimitOrder.setLevel(runners[properIndex].getExpectedDcLevel());
                        } else {
                            cancelSellLimitOrder();
                        }
                    } else {
                        sellLimitOrder.setLevel(runners[properIndex].getExpectedDcLevel());
                    }
                    return true;
                }
            }
        }
        return false;
    }


    private void cancelSellLimitOrder(){
        sellLimitOrder = null;
    }

    // TODO: to implement the cancel methods instead of the plain "= null".


    private void cancelBuyLimitOrder(){
        buyLimitOrder = null;
    }


    /**
     * The method corrects thresholds.
     */
    private void correctThresholdsAndVolumes(float inventory){
        if (Math.abs(inventory) < 15){
            uniteSizeFromInventory = originalUnitSize;
        } else if (Math.abs(inventory) >= 15 && Math.abs(inventory) < 30){
            uniteSizeFromInventory = originalUnitSize / 2;
        } else {
            uniteSizeFromInventory = originalUnitSize / 4;
        }
    }


    /**
     * The method checks the level returned by the Liquidity indicator and changes unit size. Described on the page 15 of the
     * "Alpha..."
     */
    private double computeLiqUniteCoef(double liquidity){
        double liqUnitCoef;
        if (0.5 <= liquidity){
            liqUnitCoef = 1.0;
        } else if (0.1 <= liquidity && liquidity < 0.5){
            liqUnitCoef = 0.5;
        } else {
            liqUnitCoef = 0.1;
        }
        return liqUnitCoef;
    }


    /**
     * The method compares my current total PnL and the assigned target level.
     * @return true if the PnL is greater or equal then the target PnL
     */
    private boolean positionCrossedTargetPnL(Price price){
        return (getPositionTotalPnL(price) >= targetAbsPnL);
    }


    /**
     * The method returns total PnL based on realized and unrealized ones.
     * @param price is current market price
     * @return total PnL
     */
    private double getPositionTotalPnL(Price price){
        return getPositionProfit(price);
    }

    /**
     * The method computes total profit of the opened posiition
     * @param price current market price
     * @return profit of the opened position
     */
    public double getPositionProfit(Price price){
        return positionRealizedProfit + getPositionUnrealizedProfit(price);
    }


    /**
     * The method computes unrealized profit of the opened position
     * @param price is current market price
     * @return unrealized profit
     */
    private double getPositionUnrealizedProfit(Price price){
        if (disbalancedOrders.size() != 0){
            double marketPrice = (longShort == 1 ? price.getBid() : price.getAsk());
            double unrealizedProfit = 0.0;
            for (LimitOrder limitOrder : disbalancedOrders){
                unrealizedProfit += (marketPrice - limitOrder.getLevel()) * limitOrder.getType() * limitOrder.getVolume();
            }
            return unrealizedProfit;
        }
        return 0.0;
    }



    /**
     * The method should be called as soon as the current PnL is bigger or equal then the targetPnL.
     */
    private void closePosition(Price price){
        marketOrderToClosePosition(price);
        realizedProfit += positionRealizedProfit;
        positionRealizedProfit = 0.0;
        inventory = 0;
        cancelBuyLimitOrder();
        cancelSellLimitOrder();
        correctThresholdsAndVolumes(inventory);
    }


    /**
     * The method creates a market order of volume of all disbalanced orders to close the current position
     * @param price is current market price
     * @return true if no problems
     */
    private boolean marketOrderToClosePosition(Price price){
        double orderPrice = (longShort == 1 ? price.getBid() : price.getAsk());
        for (LimitOrder limitOrder : disbalancedOrders){
            double priceMove = (orderPrice - limitOrder.getLevel()) * limitOrder.getType();
            positionRealizedProfit += priceMove * limitOrder.getVolume();
        }
        disbalancedOrders = new LinkedList<>();
        return true;
    }


    private void computeTargetAbsPnL(){
        // TODO
    }


    public double getRealizedProfit(){
        return realizedProfit;
    }


}
