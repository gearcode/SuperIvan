package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;
import com.dukascopy.api.drawings.IVerticalLineChartObject;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A07策略
 * 策略概述：基础还是双向开仓。
 * 取我们规定的一个时间点，比如说，21：05 的价格，以此点为基础进行双向开仓，
 * 在此点位以上，上涨开趋势单，下跌开马丁单，马丁最多开三次，即248手加仓，
 * 行情等于前期高低点时，平掉马丁单，系统收盘前5分钟，这个时间我们取交易所，
 * 或是我们规定好4小时全平（约等于4小时，因为我们前面取的是一个5分钟的时间点）。
 * 再开始，以这个小时之后的5分钟为点做双开循环程序。加个不平马丁单的参数，选择平马丁或否马丁。
 * @author liteng
 *
 */
public class A07 implements IStrategy {

	public static final int ORDER_TIMEOUT = 2000;
	public static final SimpleDateFormat dateFormart = new SimpleDateFormat("yyyyMMdd_HHmmss");

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("趋势单间隔")
	public Integer gap_normal = 50;

	@Configurable("趋势单手数")
	public double lots_normal = 0.001;
	
	@Configurable("马丁单间隔")
	public Integer gap_martin = 20;

	@Configurable("马丁单基础手数")
	public double lots_martin = 0.002;
	
	@Configurable("马丁单总数限制")
	public double martin_times = 3;

	@Configurable("马丁倍数")
	public double martin_multiple = 2;
	
	@Configurable("平马丁单")
	public boolean martin_close_enable = true;
	
	@Configurable("交易周期(分钟)")
	public Integer trading_period = 240;
	
	@Configurable("周期开始间隔(分钟)")
	public Integer trading_period_gap = 5;
	
	@Configurable("周日交易开始时间(小时)")
	public Integer trading_sunday_begin = 22;
	
	@Configurable("周五交易结束时间(小时)")
	public Integer trading_friday_end = 18;
	
	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;

	GridSystem gridsNormalAsk = null, gridsNormalBid = null;
	GridSystem gridsMartinAsk = null, gridsMartinBid = null;
	
	// 待关闭的马订单
	List<IOrder> waitingMartinOrders = new ArrayList<IOrder>();
	
	// 每个交易周期开始时间
	long trading_period_time_start = 0;
	
	// trading time
	boolean isTradingTime = false;
	
	GridCallback gridsNormalCallbackAsk = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {
			
			// first grid
			if(leave == null) return;
		
			// up
			if(leave.getOffset() < enter.getOffset()) {
				
				/*
				 * 趋势单
				 * 条件: 当前格子没有趋势单 && (最大趋势单为null || 当前价格大于最大趋势单价格)
				 */
				IOrder maxNormalOrder = getOrder(gridsNormalAsk, OrderType.Normal, true);
				if(!existOrder(enter, OrderType.Normal) && (maxNormalOrder == null || enter.getStart() > maxNormalOrder.getOpenPrice())) {
					// normal order
					submitOrder(enter, OrderType.Normal, IEngine.OrderCommand.BUY, lots_normal);
				}

			}
			// down
			else {}

		}
	};

	GridCallback gridsNormalCallbackBid = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {

			// first grid
			if(leave == null) return;

			// down
			if(leave.getOffset() > enter.getOffset()) {

				/*
				 * 趋势单
				 * 条件: 当前格子没有趋势单 && (最小趋势单为null || 当前价格小于最小趋势单价格)
				 */
				IOrder minNormalOrder = getOrder(gridsNormalBid, OrderType.Normal, false);
				if(!existOrder(enter, OrderType.Normal) && (minNormalOrder == null || enter.getStart() < minNormalOrder.getOpenPrice())) {
					// normal order
					submitOrder(enter, OrderType.Normal, IEngine.OrderCommand.SELL, lots_normal);
				}

			}
			// up
			else {}

		}
	};

	GridCallback gridsMartinCallbackAsk = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {

			// first grid
			if(leave == null) return;

			// up
			if(leave.getOffset() < enter.getOffset()) {
				/*
				 * 判断是否需要平掉所有马丁单
				 */
				List<IOrder> martinOrders = getOrders(gridsMartinAsk, OrderType.Martin);
				if(martinOrders.size() > 0) {
					IOrder maxMartinOrder = getOrder(gridsMartinAsk, OrderType.Martin, true);
					if(leave.getStart() > maxMartinOrder.getOpenPrice()) {
						// 平马丁
						if(martin_close_enable) {
							gridsMartinAsk.closeAndRemoveOrder(martinOrders);
						} else {
							waitingMartinOrders.addAll(martinOrders);
							gridsMartinAsk.removeOrder(martinOrders);
						}
					}
				}
			}
			// down
			else {
				/*
				 * 马丁单
				 */
				IOrder minMartinOrder = getOrder(gridsMartinAsk, OrderType.Martin, false);
				int martinOrderCount = getOrders(gridsMartinAsk, OrderType.Martin).size();
				// 总单数限制
				if(martinOrderCount < martin_times) {
					// 判断是否下马丁单
					if(!existOrder(enter, OrderType.Martin) && (minMartinOrder == null || enter.getStart() < minMartinOrder.getOpenPrice())) {
						// 马丁单手数
						double lots = lots_martin;
						if(minMartinOrder != null) {
							lots = minMartinOrder.getAmount() * martin_multiple;
						}
						submitOrder(enter, OrderType.Martin, IEngine.OrderCommand.BUY, lots);
					}
				}
			}

		}
	};

	GridCallback gridsMartinCallbackBid = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {

			// first grid
			if(leave == null) return;

			// up
			if(leave.getOffset() < enter.getOffset()) {
				/*
				 * 马丁单
				 */
				IOrder maxMartinOrder = getOrder(gridsMartinBid, OrderType.Martin, true);
				int martinOrderCount = getOrders(gridsMartinBid, OrderType.Martin).size();
				// 总单数限制
				if(martinOrderCount < martin_times) {
					// 判断是否下马丁单
					if(!existOrder(enter, OrderType.Martin) && (maxMartinOrder == null || enter.getStart() > maxMartinOrder.getOpenPrice())) {
						// 马丁单手数
						double lots = lots_martin;
						if(maxMartinOrder != null) {
							lots = maxMartinOrder.getAmount() * martin_multiple;
						}
						submitOrder(enter, OrderType.Martin, IEngine.OrderCommand.SELL, lots);
					}	
				}
			}
			// down
			else {
				/*
				 * 判断是否需要平掉所有马丁单
				 */
				List<IOrder> martinOrders = getOrders(gridsMartinBid, OrderType.Martin);
				if(martinOrders.size() > 0) {
					IOrder minMartinOrder = getOrder(gridsMartinBid, OrderType.Martin, false);
					if(leave.getStart() < minMartinOrder.getOpenPrice()) {
						// 平马丁
						if(martin_close_enable) {
							gridsMartinBid.closeAndRemoveOrder(martinOrders);
						} else {
							waitingMartinOrders.addAll(martinOrders);
							gridsMartinBid.removeOrder(martinOrders);
						}
					}
				}
			}
			
		}
	};
	
	
	@Override
	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		indicators = context.getIndicators();
		chart = context.getChart(instrument);
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {

		if(instrument.equals(this.instrument)) {

			/*
			 * 交易时间进入非交易时间, 订单全平
			 */
			if(isTradingTime && !isTradingTime(tick.getTime())) {
				//订单全平
				closeAllOrders();
				log("==== close all / non-trading time");
			}
			
			isTradingTime = isTradingTime(tick.getTime());
			if(!isTradingTime) return;
			
			// init trading_period_time_start
			if(trading_period_time_start == 0) {
				trading_period_time_start = tick.getTime();
			}
			
			// 周期开始时间 > 周期间隔 则开始交易
			if(tick.getTime() - trading_period_time_start < TimeUnit.MINUTES.toMillis(trading_period_gap)) {
				return;
			}

			/*
			 * timing / N minutes
			 */
			if((tick.getTime() - trading_period_time_start) >= TimeUnit.MINUTES.toMillis(trading_period)) {
				trading_period_time_start = tick.getTime();
				// close all
				closeAllOrders();
				// draw line
				String timeStr = dateFormart.format(tick.getTime());
			    IChartObjectFactory factory = chart.getChartObjectFactory();
				String lineText = timeStr + " close all";
				IVerticalLineChartObject line = factory.createVerticalLine(lineText, tick.getTime());
				line.setLocked(true);
				line.setTooltip(lineText);
				line.setText(lineText);
				chart.add(line);
				// log
				log("==== close all / " + trading_period + " minutes / " + timeStr + " ====");
			}
			
			/*
			 * 每周开始结束判断
			 * 判断时间是否在非交易时间
			 */
			
			double ask = tick.getAsk(), bid = tick.getBid();
			
			// init grid system
			double realPipValue = instrument.getPipValue() * 0.1;
			
			// 初始化趋势单格子
			if(gridsNormalAsk == null && gridsNormalBid == null) {
				/*
				 *  init grid system
				 */
				gridsNormalAsk = new GridSystem("ask_normal_grids", ask, gap_normal * realPipValue, instrument.getPipScale() + 1);
				gridsNormalAsk.setGridCallback(gridsNormalCallbackAsk);
				gridsNormalAsk.setConsole(console);
				log("AskNormalGrid create: " + "ask_normal_grids, " + ask + ", " + gap_normal + ", " + (instrument.getPipScale() + 1));
				gridsNormalBid = new GridSystem("bid_normal_grids", bid, gap_normal * realPipValue, instrument.getPipScale() + 1);
				gridsNormalBid.setGridCallback(gridsNormalCallbackBid);
				gridsNormalBid.setConsole(console);
				log("BidNormalGrid create: " + "bid_normal_grids, " + bid + ", " + gap_normal + ", " + (instrument.getPipScale() + 1));
			}
			
			// 初始化马丁单格子
			if(gridsMartinAsk == null && gridsMartinBid == null) {
				/*
				 *  init grid system
				 */
				gridsMartinAsk = new GridSystem("ask_martin_grids", ask, gap_martin * realPipValue, instrument.getPipScale() + 1);
				gridsMartinAsk.setGridCallback(gridsMartinCallbackAsk);
				gridsMartinAsk.setConsole(console);
				log("AskMartinGrid create: " + "ask_martin_grids, " + ask + ", " + gap_martin + ", " + (instrument.getPipScale() + 1));
				gridsMartinBid = new GridSystem("bid_martin_grids", bid, gap_martin * realPipValue, instrument.getPipScale() + 1);
				gridsMartinBid.setGridCallback(gridsMartinCallbackBid);
				gridsMartinBid.setConsole(console);
				log("BidMartinGrid create: " + "bid_martin_grids, " + bid + ", " + gap_martin + ", " + (instrument.getPipScale() + 1));
			}
			
			/*
			 * offer tick
			 */
			if(gridsNormalAsk != null && gridsNormalBid != null) {
				gridsNormalAsk.offer(ask);
				gridsNormalBid.offer(bid);
			}
			
			/*
			 * offer tick
			 */
			if(gridsMartinAsk != null && gridsMartinBid != null) {
				gridsMartinAsk.offer(ask);
				gridsMartinBid.offer(bid);
			}
		}
	}

	@Override
	public void onBar(Instrument instrument, Period period, IBar askBar,
                      IBar bidBar) throws JFException {
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
	
	private void closeAllOrders() {
		gridsNormalAsk.closeAndRemoveOrder(gridsNormalAsk.getAllOrders());
		gridsNormalBid.closeAndRemoveOrder(gridsNormalBid.getAllOrders());
		gridsMartinAsk.closeAndRemoveOrder(gridsMartinAsk.getAllOrders());
		gridsMartinBid.closeAndRemoveOrder(gridsMartinBid.getAllOrders());
		closeOrder(waitingMartinOrders);
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

	/**
	 * 交易时间判断
	 * @param time
	 * @return
	 */
	public boolean isTradingTime(long time) {
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(time);
		int day_of_week = cal.get(Calendar.DAY_OF_WEEK);
		int hour_of_day = cal.get(Calendar.HOUR_OF_DAY);
		
		//周六全天不交易
		if(day_of_week == Calendar.SATURDAY) {
			return false;
		}
		
		//周五18:00点后不交易(包含)
		if(day_of_week == Calendar.FRIDAY && hour_of_day > trading_friday_end) {
			return false;
		}
		
		//周日22:00前不交易(包含)
		if(day_of_week == Calendar.SUNDAY && hour_of_day < trading_sunday_begin) {
			return false;
		}
		
		return true;
	}
	
	/**
	 * 提交订单
	 * @param grid
	 * @param type
	 * @param command
	 * @param lots
	 */
	public void submitOrder(Grid grid, OrderType type, OrderCommand command, double lots) {
		String orderLabel = "SuperIvan_Order_" + command + "_" + type + "_" + (grid.getOffset() < 0 ? "n" + (-grid.getOffset()) : grid.getOffset()) + "_" + System.currentTimeMillis();
		try {
			IOrder order = engine.submitOrder(
					orderLabel,
					instrument,
					command,
					lots);
			if(command.equals(OrderCommand.BUY) || command.equals(OrderCommand.SELL)) {
				order.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
			}
			log("[Order][Open] " + orderLabel + ", price: " + order.getOpenPrice() + ", lots: " + lots);
			grid.getOrders().add(order);
		} catch (JFException e) {
			e.printStackTrace();
			log("[ERROR] submit order error: " + e.getLocalizedMessage());
		}
	}
	
	/**
	 * 平仓
	 * @param grid
	 * @param order
	 */
	public void closeOrder(Grid grid, IOrder order) {
		try {
			order.close();
			order.waitForUpdate(ORDER_TIMEOUT, State.CLOSED);
			log("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
			grid.getOrders().remove(order);
		} catch (JFException e) {
			e.printStackTrace();
			log("[ERROR] close order error: " + e.getLocalizedMessage());
		}
	}
	
	public void closeOrder(List<IOrder> orders) {
		for(int i = orders.size() - 1; i >= 0; i--) {
			IOrder order = orders.get(i);
			try {
				order.close();
				order.waitForUpdate(ORDER_TIMEOUT, State.CLOSED);
				log("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
				orders.remove(i);
			} catch (JFException e) {
				e.printStackTrace();
				log("[ERROR] close order error: " + e.getLocalizedMessage());
			}
		}
	}
	
	/************************************
	 * 
	 * 马丁类开始
	 * @author liteng
	 *
	 ************************************/
	/**
	 * 判断是否为指定类型订单
	 * @param order
	 * @return
	 */
	private boolean isOrder(IOrder order, OrderType type) {
		switch (type) {
		case Normal:
			return order.getLabel().contains("Normal");
		case Martin:
			return order.getLabel().contains("Martin"); 
		default:
			return false;
		}
	}
	
	/**
	 * 判断格子中是否存在指定类型订单
	 * @param grid
	 * @return
	 */
	private boolean existOrder(Grid grid, OrderType type) {
		List<IOrder> orders = grid.getOrders();
		for (IOrder order : orders) {
			if(isOrder(order, type)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 获取最大or最小价格的订单
	 * @param gridSystem
	 * @param type
	 * @param max true:最大 / false:最小
	 * @return
	 */
	private IOrder getOrder(GridSystem gridSystem, OrderType type, boolean max) {
		IOrder result = null;
		
		List<Grid> grids = new ArrayList<Grid>(gridSystem.getGridsAbove());
		grids.addAll(gridSystem.getGridsUnder());
		
		// foreach all grids
		for (Grid grid : grids) {
			
			//foreach all order of this grid
			List<IOrder> orders = grid.getOrders();
			for (IOrder order : orders) {
				if(!isOrder(order, type)) continue;
				if(result == null) {
					result = order;
				} else {
					// 比较大小
					if(max) {
						if(result.getOpenPrice() < order.getOpenPrice()) result = order;
					} else {
						if(result.getOpenPrice() > order.getOpenPrice()) result = order;
					}
				}
			}
			
		}
		
		return result;
	}

	/**
	 * 获取最大or最小价格的order
	 * @param grid
	 * @param type
	 * @param max true:最大 / false:最小
	 * @return
	 */
	private IOrder getOrder(Grid grid, OrderType type, boolean max) {
		IOrder result = null;
		
		//foreach all order of this grid
		List<IOrder> orders = grid.getOrders();
		for (IOrder order : orders) {
			if(!isOrder(order, type)) continue;
			if(result == null) {
				result = order;
			} else {
				// 比较大小
				if(max) {
					if(result.getOpenPrice() < order.getOpenPrice()) result = order;
				} else {
					if(result.getOpenPrice() > order.getOpenPrice()) result = order;
				}
			}
		}
		
		return result;
	}

	/**
	 * 获得所有指定类型订单
	 * @param gridSystem
	 * @return
	 */
	private List<IOrder> getOrders(GridSystem gridSystem, OrderType type) {
		List<IOrder> result = new ArrayList<IOrder>();
		
		List<Grid> grids = gridSystem.getAllGrids();
		for (Grid grid : grids) {
			List<IOrder> orders = grid.getOrders();
			for (IOrder order : orders) {
				if(isOrder(order, type)) {
					result.add(order);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * 获取最大亏损单
	 * @param gridSystem
	 * @return
	 */
	public IOrder getMaxLossOrder(GridSystem gridSystem) {
		IOrder result = null;
		List<IOrder> orders = gridSystem.getAllOrders();
		for (IOrder order : orders) {
			if(result == null || result.getProfitLossInAccountCurrency() > order.getProfitLossInAccountCurrency()) {
				result = order;
			}
		}
		return result;
	}
	
	/**
	 * 计算指定订单总手数
	 * 
	 * @param gridSystem
	 * @param type
	 * @return
	 */
	public double countOrdersLots(GridSystem gridSystem, OrderType type) {
		List<IOrder> orders = getOrders(gridSystem, type);
		return countLots(orders);
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
		return count;
	}
	
	enum OrderType {
		Normal, Martin
	}
	
	/************************************
	 * 
	 * 基础类开始
	 * @author liteng
	 *
	 ************************************/
	class GridSystem {
		
		private String name;
		
		private IChart chart;
		
		private int pointScale;
		private DecimalFormat df = new DecimalFormat("#");
		
		private double pointgap;
		private double startPrice;
	
		private List<Grid> gridsAbove = new ArrayList<Grid>();
		private List<Grid> gridsUnder = new ArrayList<Grid>();
		
		private Grid lastGrid;
		
		private Integer highest = null;
		private Integer lowest  = null;
		
		GridCallback gridCallback;
		
		private IConsole console;
		
		public GridSystem(String name, double startPrice, double pointGap, int pointScale) {
			this(name, startPrice, pointGap, pointScale, null);
		}
		
		public GridSystem(String name, double startPrice, double pointGap, int pointScale, IChart chart) {
			this.name = name;
			this.chart = chart;
			this.pointgap = pointGap;
			this.startPrice = startPrice;
			this.pointScale = pointScale;
			
			if(pointScale > 0) {
				StringBuilder sb = new StringBuilder("#.");
				for(int i=0;i<pointScale;i++) {
					sb.append('0');
				}
				this.df = new DecimalFormat(sb.toString());
			}
		}
		
		public Grid offer(double price) {
			Grid grid = getGridByPrice(price);
			
			// grid change
			if(lastGrid == null || !lastGrid.equals(grid)) {
				if(gridCallback != null) {
					gridCallback.onGridChange(lastGrid, grid, price);
				}
			}
			
			// highest & lowest
			if(highest == null || highest < grid.getOffset()) {
				highest = grid.getOffset();
			}
			if(lowest == null || lowest > grid.getOffset()) {
				lowest = grid.getOffset();
			}
			
			lastGrid = grid;
			return grid;
		}
		
		private int countOffset(double price) {
			int front = 0;
			if(price >= startPrice) {
				front = 1;
			} else {
				front = -1;
			}
			double d = (price - startPrice) / pointgap + front;
			
			/*
			 *  当前价格大于初始价格时, 格子的价格区间为[small, large)
			 *  当前价格小于初始价格时, 格子的价格区间为[large, small)
			 *  当前价格等于初始价格时, 默认为+1格子
			 */
			return new BigDecimal(d).setScale(pointScale, BigDecimal.ROUND_HALF_UP).intValue();
		}
		
		public Grid getGridByOffset(int offset) {
			if(offset > 0) {
				while(gridsAbove.size() < offset) {
					Grid grid = new Grid(this, gridsAbove.size() + 1);
					gridsAbove.add(grid);
				}
				return gridsAbove.get(offset - 1);
			} else if (offset < 0) {
				while(gridsUnder.size() < -offset) {
					Grid grid = new Grid(this, -gridsUnder.size() - 1);
					gridsUnder.add(grid);
				}
				return gridsUnder.get(-offset - 1);
			} else {
				return null;
			}
		}
		
		public List<Grid> getAllGrids() {
			List<Grid> grids = new ArrayList<Grid>(gridsAbove);
			grids.addAll(gridsUnder);
			return grids;
		}
	
		public void removeOrder(List<IOrder> orders) {
			for (IOrder order : orders) {
				removeOrder(order);
			}
		}
		
		public void removeOrder(IOrder order) {
			List<Grid> grids = this.getAllGrids();
			for (Grid grid : grids) {
				grid.getOrders().remove(order);
			}
		}
	
		public void closeAndRemoveOrder(List<IOrder> orders) {
			for (IOrder order : orders) {
				closeAndRemoveOrder(order);
			}
		}
		
		public void closeAndRemoveOrder(IOrder order) {
			for (Grid grid : gridsAbove) {
				List<IOrder> orders = grid.getOrders();
				for (int i = orders.size() - 1; i >= 0; i--) {
					IOrder o = orders.get(i);
					if(o.equals(order)) {
						closeOrder(grid, order);
					}
				}
			}
	
			for (Grid grid : gridsUnder) {
				List<IOrder> orders = grid.getOrders();
				for (int i = orders.size() - 1; i >= 0; i--) {
					IOrder o = orders.get(i);
					if(o.equals(order)) {
						closeOrder(grid, order);
					}
				}
			}
		}
		
		/**
		 * Get all orders
		 * @return
		 */
		public List<IOrder> getAllOrders() {
			List<IOrder> result = new ArrayList<IOrder>();
			List<Grid> grids = getAllGrids();
			for (Grid grid : grids) {
				result.addAll(grid.getOrders());
			}
			return result;
		}
		
		public IChart getChart() {
			return chart;
		}
	
		public Grid getGridByPrice(double price) {
			return getGridByOffset(countOffset(price));
		}
	
		public DecimalFormat getDf() {
			return df;
		}
	
		public void setGridCallback(GridCallback gridCallback) {
			this.gridCallback = gridCallback;
		}
	
		public double getGap() {
			return pointgap;
		}
	
		public double getStartPrice() {
			return startPrice;
		}
	
		public int getPointScale() {
			return pointScale;
		}
	
		public List<Grid> getGridsAbove() {
			return gridsAbove;
		}
	
		public List<Grid> getGridsUnder() {
			return gridsUnder;
		}
	
		public String getName() {
			return name;
		}
	
		public void setConsole(IConsole console) {
			this.console = console;
		}
	
	}
	
	class Grid {
	
		private GridSystem gridSystem;
		
		private List<IOrder> orders = new ArrayList<IOrder>();
		
		// -3, -2, -1, 1, 2, 3 ...
		private Integer offset;
		
		// contain
		private Double start;
		
		// not contain
		private Double end;
		
		public Grid(GridSystem gridSystem, int offset) {
			
			this.gridSystem = gridSystem;
			this.offset = offset;
			
			double gap = gridSystem.getGap();
			double origin = gridSystem.getStartPrice();
			int pointScale = gridSystem.getPointScale();
			
			this.setEnd(new BigDecimal(origin + offset * gap).setScale(pointScale, BigDecimal.ROUND_HALF_UP).doubleValue());
			this.setStart(new BigDecimal(this.getEnd() + (offset > 0 ? -gap : gap)).setScale(pointScale, BigDecimal.ROUND_HALF_UP).doubleValue());
			
			if(gridSystem.getChart() != null) {
				IChartObjectFactory cof = gridSystem.getChart().getChartObjectFactory();
				IHorizontalLineChartObject line = cof.createHorizontalLine(Math.random() + "", this.getStart());
				gridSystem.getChart().add(line);
			}
		}
	
		public GridSystem getGridSystem() {
			return gridSystem;
		}
	
		public Integer getOffset() {
			return offset;
		}
	
		public void setOffset(Integer offset) {
			this.offset = offset;
		}
	
		public Double getStart() {
			return start;
		}
	
		public void setStart(Double start) {
			this.start = start;
		}
	
		public Double getEnd() {
			return end;
		}
	
		public void setEnd(Double end) {
			this.end = end;
		}
	
		public List<IOrder> getOrders() {
			return orders;
		}
		
		@Override
		public boolean equals(Object o) {
			if(o instanceof Grid) {
				return this.getOffset().equals(((Grid) o).getOffset()); 
			}
			return super.equals(o);
		}
		
		@Override
		public String toString() {
			return this.getOffset() + " [" + this.gridSystem.getDf().format(this.getStart()) + ", " + this.gridSystem.getDf().format(this.getEnd()) + ")";
		}
	}
	
	interface GridCallback {
		public void onGridChange(Grid leave, Grid enter, double price);
	}
}
