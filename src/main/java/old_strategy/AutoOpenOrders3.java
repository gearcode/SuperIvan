package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage.Type;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;

import javax.swing.*;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 双向格子开仓
 * 当盈利到达止盈金额时平仓并重新设置格子
 * 当单向开单数达到止盈格数时平仓并重新设置格子
 * @author liteng
 *
 */
public class AutoOpenOrders3 implements IStrategy {

	public static final int ORDER_TIMEOUT = 2000;
	public static final SimpleDateFormat dateFormart = new SimpleDateFormat("yyyyMMdd_HHmmss");

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("趋势单间隔")
	public Integer gap_normal = 20;

	@Configurable("趋势单手数")
	public double lots_normal = 0.001;

	@Configurable("止盈格数(单方向)")
	public int stop_orders = 30;

	@Configurable("止盈金额(账户当前货币单位)")
	public double stop_profit = 10;

	@Configurable("滑点数")
	public int slippage = 10;

	@Configurable("连续执行")
	public boolean auto = false;
	
	IContext context = null;
	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;
	
	// 全局开关, 用来保证平仓过程中程序停止开仓
	private boolean running = true;
	
	private double price_max;
	private double price_min;

	@Override
	public void onStart(IContext context) throws JFException {
		this.context = context;
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		indicators = context.getIndicators();
		chart = context.getChart(instrument);
		
		List<IOrder> orders = engine.getOrders(this.instrument);
		if(orders.size() > 0) {
			UI.Alert("该币种已存在订单: " + instrument.name());
			context.stop();
		}
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		if(!running) return;
		if(instrument.equals(this.instrument)) {
			double ask = tick.getAsk(), bid = tick.getBid();
			
			List<IOrder> orders = engine.getOrders(this.instrument);
			
			// 判断盈利达到目标
			if(countProfit(orders) >= stop_profit) {
				closeAll(2);
				return;
			}
			
			// 初始化挂单, 以当前bid价格上下固定间隔挂单
			if(orders.size() == 0) {
				// 开挂单
				for(int i=1; i <= stop_orders; i++) {
					IOrder orderBuyStop = engine.submitOrder(
							"Order_BuyStop_" + i + "_" + StratUtils.generateLabel(),
							instrument,
							OrderCommand.BUYSTOP,
							lots_normal,
							bid + i*gap_normal*instrument.getPipValue()*0.1,
							slippage);
					orderBuyStop.waitForUpdate(ORDER_TIMEOUT, State.OPENED);
					log("[Order] Submit success! " + orderBuyStop);
					IOrder orderSellStop = engine.submitOrder(
							"Order_SellStop_" + i + "_" + StratUtils.generateLabel(),
							instrument,
							OrderCommand.SELLSTOP,
							lots_normal,
							bid - i*gap_normal*instrument.getPipValue()*0.1,
							slippage);
					orderSellStop.waitForUpdate(ORDER_TIMEOUT, State.OPENED);
					log("[Order] Submit success! " + orderSellStop);
				}
				
				// 设置最大/小价格
				price_max = bid + (stop_orders+1)*gap_normal*instrument.getPipValue()*0.1;
				price_min = bid - (stop_orders+1)*gap_normal*instrument.getPipValue()*0.1;
				
				// 画线
				chart.removeAll();
				IChartObjectFactory cof = chart.getChartObjectFactory();
				IHorizontalLineChartObject line_max = cof.createHorizontalLine("Line_Max_" + price_max, price_max);
				IHorizontalLineChartObject line_min = cof.createHorizontalLine("Line_Min_" + price_min, price_min);
				chart.add(line_max);
				chart.add(line_min);
				
				log("Order submit done!");
			} else {
				// 判断价格已突破最大/小值
				if(bid > price_max || bid < price_min) {
					closeAll(1);
				}
			}
		}
	}

	@Override
	public void onBar(Instrument instrument, Period period, IBar askBar,
                      IBar bidBar) throws JFException {
	}

	@Override
	public void onMessage(IMessage message) throws JFException {
		// 判断当前币种有订单 FILL_OK
		if(message.getType().equals(Type.ORDER_FILL_OK)) {
			IOrder o = message.getOrder();
			if(o.getInstrument().equals(instrument)) {
				/*
				 * 判断多空手数如果不同则合并
				 */
				List<IOrder> orders = engine.getOrders(instrument);
				List<IOrder> fillOrders = new ArrayList<IOrder>();
				for (IOrder order : orders) {
					if(order.getState().equals(State.FILLED)) {
						fillOrders.add(order);
					}
				}
				
				if(fillOrders.size() > 1) {
					List<IOrder> longOrders = new ArrayList<IOrder>();
					List<IOrder> shortOrders = new ArrayList<IOrder>();
					for (IOrder order : fillOrders) {
						switch (order.getOrderCommand()) {
						case BUY:
							longOrders.add(order);
							break;
						case SELL:
							shortOrders.add(order);
							break;
						default:
							break;
						}
					}
					
					// 判断非完全对冲
					if(countLots(longOrders) != countLots(shortOrders)) {
						// 合并全部
						IOrder order = engine.mergeOrders("Order_Merge_" + StratUtils.generateLabel(), fillOrders);
						order.waitForUpdate(ORDER_TIMEOUT, IOrder.State.FILLED);
						log("[Order] Merge success!" + order);
					}
				}
			}
		}
	}

	@Override
	public void onAccount(IAccount account) throws JFException {
	}

	@Override
	public void onStop() throws JFException {
	}
	
	/**
	 * 打印日志
	 * @param str
	 */
	public void log(String... str) {
		StringBuilder sb = new StringBuilder();
		for (String s : str) {
			sb.append(s);
		}
		console.getOut().println(sb.toString());
	}
	
	private void closeAll(int type) {
		running = false;
		String reason = "unknow";
		switch(type) {
		case 1:
			reason = "stop_orders: " + stop_orders;
			break;
		case 2:
			reason = "stop_profit: " + stop_profit;
			break;
		}
		
		try {
			log("==== close begin / " + reason + " ====");
			List<IOrder> orders = engine.getOrders(this.instrument);
			for (IOrder order : orders) {
				order.close();
				// wait for update
				switch(order.getOrderCommand()) {
					case BUY:
					case SELL:
						order.waitForUpdate(ORDER_TIMEOUT, State.CLOSED);
						break;
					case BUYSTOP:
					case SELLSTOP:
						order.waitForUpdate(ORDER_TIMEOUT, State.CANCELED);
						break;
					default:
						break;
				}
				log("[Order][Close] " + order.getLabel() + ", " + order.getOrderCommand() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
			}
			
			log("==== close success / " + reason + " ====");
			if(!auto) {
				context.stop();
			}
		} catch (JFException e) {
			e.printStackTrace();
		}
		
		running = true;
	}

	/**
	 * 获取订单总获利
	 * @param orders
	 * @return
	 */
	public double countProfit(List<IOrder> orders) {
		double count = 0.0;
		for (IOrder order : orders) {
			count += order.getProfitLossInAccountCurrency();
		}
		return count;
	}
	
	/**
	 * 获取订单总手数
	 * @param orders
	 * @return
	 */
	public double countLots(List<IOrder> orders) {
		double count = 0.0;
		for (IOrder order : orders) {
			count += order.getAmount();
		}
		return new BigDecimal(count).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue();
	}


	static class UI {
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

	static class StratUtils {
		private static final Random random = new Random();
		public static String generateLabel() {
			String label = "JF";
			while (label.length() < 10) {
				label = label + Integer.toString(random.nextInt(100000000), 36);
			}
			label = label.substring(0, 9);
			label = label.toLowerCase();
			return label;
		}
	}
}
