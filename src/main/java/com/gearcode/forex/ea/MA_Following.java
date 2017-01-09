package com.gearcode.forex.ea;

import com.dukascopy.api.*;

import java.math.BigDecimal;

import static java.math.BigDecimal.ROUND_HALF_UP;

/**
 * Created by jason on 17/1/4.
 */
public class MA_Following implements IStrategy {

    @Configurable("货币对")
    public Instrument instrument = Instrument.EURUSD;

    private IEngine engine = null;
    private IIndicators indicators = null;
    private IConsole console = null;

    BigDecimal pre_ma;

    IOrder order;

    @Override
    public void onStart(IContext context) throws JFException {
        engine = context.getEngine();
        indicators = context.getIndicators();
        this.console = context.getConsole();
        console.getOut().println("Started");

    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {

        if(instrument.equals(this.instrument)) {

            double _ma = indicators.ema(instrument, Period.ONE_MIN, OfferSide.BID, IIndicators.AppliedPrice.MEDIAN_PRICE, 10, 0);
            BigDecimal ma = new BigDecimal(_ma).setScale(5, ROUND_HALF_UP);

            if(null == pre_ma) {
                pre_ma = ma;
            } else {
                log(pre_ma.toString() +","+ ma.toString());
                // up
                if(ma.compareTo(pre_ma) > 0) {
                    if(order == null || !order.getOrderCommand().equals(IEngine.OrderCommand.BUY)) {
                        reverse(true, instrument);
                    }
                }

                // down
                if(ma.compareTo(pre_ma) < 0) {
                    if(order == null || !order.getOrderCommand().equals(IEngine.OrderCommand.SELL)) {
                        reverse(false, instrument);
                    }
                }

                pre_ma = ma;
            }
        }
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {

    }

    @Override
    public void onMessage(IMessage message) throws JFException {

    }

    @Override
    public void onAccount(IAccount account) throws JFException {

    }

    @Override
    public void onStop() throws JFException {

    }

    private void reverse(boolean rising, Instrument instrument) {
        try {
            if(order != null) {
                order.close();
                order.waitForUpdate(2000, IOrder.State.CLOSED);
                log("【P/S】: " + order.getProfitLossInPips());
            }

            order = engine.submitOrder(
                    "MA_Following_" + System.currentTimeMillis(),
                    instrument,
                    rising ? IEngine.OrderCommand.BUY : IEngine.OrderCommand.SELL,
                    0.01);
            order.waitForUpdate(2000, IOrder.State.FILLED);

            log("Reverse: " + order.getOrderCommand() + ", " + order.getOpenPrice() + ", " + order.getAmount());
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private void log(String str) {
        System.out.println(str);
        console.getOut().println(str);
    }
}
