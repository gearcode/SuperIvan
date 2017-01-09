package old_strategy;

import com.dukascopy.api.*;

import javax.swing.*;
import java.util.List;

public class AutoOpenOrders implements IStrategy {
	
	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("开单方向")
	public OrderOpenDirection direction = OrderOpenDirection.Both;
	
	@Configurable("间隔点数(point)")
	public Integer point = 15;
	
	@Configurable("单向总订单数")
	public Integer count = 30;
	
	@Configurable("每笔订单手数")
	public double lots = 0.001;
	
	@Configurable("总止盈")
	public double profit = 25;

	@Configurable("价格触碰最后一格时平仓")
	public boolean closeAllWhenPriceReachLastGrid = true;

	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	
	private ITick startTick = null;

	@Override
	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		
		// exists orders of this instrument
		List<IOrder> orders = engine.getOrders(instrument);
		if(!orders.isEmpty()) {
			UI.Alert(instrument.name(), "已存在订单，请平掉或取消所有订单后再启动策略！");
			context.stop();
			return;
		}
		
		// last price
		ITick lastTick = history.getLastTick(instrument);
		while(lastTick == null) {
			console.getOut().println("retrieve lastTick...");
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			lastTick = history.getLastTick(instrument);
		}

		// start round
		newRound(lastTick);

		// stop old_strategy
//		context.stop();
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		if(instrument.equals(this.instrument)) {
			if(reachTarget(tick)) {
				newRound(tick);
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
	
	private void newRound(ITick tick) {
		startTick = tick;
		
		/*
		 *  close all
		 */
		console.getOut().println("正在平仓");
		try {
			List<IOrder> orders = engine.getOrders(instrument);
			for (IOrder o : orders) {
				o.close();
				o.waitForUpdate(2000);
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		console.getOut().println("已全部平仓");
		
		/*
		 *  submit orders
		 */
		console.getOut().println("正在提交挂单请求");
		long ts = System.currentTimeMillis();
		double pip = instrument.getPipValue();
		int buys = 0, sells = 0;
		for(int i=0;i<count;i++) {
			double gap_pip = (i+1)*point*pip*0.1;

			try {
				// Buy stop
				if(direction.equals(OrderOpenDirection.Both) || direction.equals(OrderOpenDirection.Buy)) {
					if(engine.submitOrder("BuyStop_" + i + "_" + ts, instrument, IEngine.OrderCommand.BUYSTOP, lots, tick.getAsk() + gap_pip) != null) {
						buys++;
					}
				}
				
				// Sell stop
				if(direction.equals(OrderOpenDirection.Both) || direction.equals(OrderOpenDirection.Sell)) {
					if(engine.submitOrder("SellStop_" + i + "_" + ts, instrument, IEngine.OrderCommand.SELLSTOP, lots, tick.getBid() - gap_pip) != null) {
						sells++;
					}
				}
			} catch (JFException e) {
				e.printStackTrace();
				UI.Alert("挂单时发生异常！" + e.getLocalizedMessage());
			}	
		}
		console.getOut().println("挂单完成, 共 " + (buys+sells) + " 单, buystop: " + buys + ", sellstop: " + sells);
		
	}
	
	private boolean reachTarget(ITick tick) {
		try {
			/*
			 * take profit ≥ profit ?
			 */
			List<IOrder> orders = engine.getOrders(instrument);
			
			double amountProfit = 0;
			for (IOrder order : orders) {
				amountProfit += order.getProfitLossInAccountCurrency();
			}
			
			if(amountProfit >= profit) {
				console.getOut().println("止盈条件成立！（" + amountProfit + " ≥ " + profit + "）");
				return true;
			}
			
			/*
			 * price reach target gird
			 */
			if(closeAllWhenPriceReachLastGrid) {
				double d = count*point*instrument.getPipValue()*0.1;
				if(direction.equals(OrderOpenDirection.Both) || direction.equals(OrderOpenDirection.Buy)) {
					if(tick.getAsk() >= startTick.getAsk() + d) {
						console.getOut().println("卖价（Ask）触碰最后一格！");
						return true;
					}
				}
				if(direction.equals(OrderOpenDirection.Both) || direction.equals(OrderOpenDirection.Sell)) {
					if(tick.getBid() <= startTick.getBid() - d) {
						console.getOut().println("买价（Bid）触碰最后一格！");
						return true;
					}
				}
			}
			
		} catch (JFException e) {
			e.printStackTrace();
			UI.Alert("获取订单失败！" + e.getLocalizedMessage());
		}
		
		return false;
	}

}

enum OrderOpenDirection {
	Buy, Sell, Both
}

class UI {
	public static void Alert(String... text) {
		String message = "";
		for (String s : text) {
			message += s;
		}
        JOptionPane optionPane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE);
        JDialog dialog = optionPane.createDialog("Alert");
        dialog.setVisible(true);
	}
}
