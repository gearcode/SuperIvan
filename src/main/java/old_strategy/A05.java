package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 任意一点双向开仓，参数间隔，单向手数参数，开仓后总盈利超2%后，回撤50%平仓。
 * 盈利不足2%时亏损1%平仓。平仓后循环开仓。无均线概念。
 * 开仓手数与基础金额成正比，1000初始金单笔开0.001。2000开0.002类推，动态推进。
 * @author liteng
 *
 */
public class A05 implements IStrategy {

	public static final int ORDER_TIMEOUT = 2000;

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("间隔点数(point)")
	public Integer gap = 50;

	@Configurable("基础手数")
	public double lots = 0.001;
	
	@Configurable("目标利润")
	public double targetProfit = 100.00;
	
	@Configurable("回撤率(%)")
	public Integer backProfitPercent = 50;
	
	@Configurable("止损")
	public double stopLoss = -50.00;
	
	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;

	GridSystem askGrids = null;
	GridSystem bidGrids = null;

	// max_profit
	private double profit_max_ask = 0.0;
	private double profit_max_bid = 0.0;
	
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
			double ask = tick.getAsk(), bid = tick.getBid();
			
			// init grid system
			double realPipValue = instrument.getPipValue() * 0.1;
			if(askGrids == null && bidGrids == null) {
				/*
				 *  init grid system
				 */
				askGrids = new GridSystem("ask_grids", ask, gap * realPipValue, instrument.getPipScale() + 1);
				askGrids.setGridCallback(askGridsCallback);
				askGrids.setConsole(console);
				log("AskGrid create: " + "ask_grids, " + ask + ", " + gap + ", " + (instrument.getPipScale() + 1));
				bidGrids = new GridSystem("bid_grids", bid, gap * realPipValue, instrument.getPipScale() + 1);
				bidGrids.setGridCallback(bidGridsCallback);
				bidGrids.setConsole(console);
				log("BidGrid create: " + "bid_grids, " + bid + ", " + gap + ", " + (instrument.getPipScale() + 1));
			}
			
			/*
			 * offer tick
			 */
			if(askGrids != null && bidGrids != null) {
				
				// 当前总利润
				double profit_ask = countProfit(askGrids.getAllOrders());
				double profit_bid = countProfit(bidGrids.getAllOrders());
				
				/*
				 * 记录最大利润
				 */
				if(profit_ask > profit_max_ask) {
					profit_max_ask = profit_ask;
				}
				if(profit_bid > profit_max_bid) {
					profit_max_bid = profit_bid;
				}
				
				/*
				 * 平仓逻辑
				 */
				// ask
				if(profit_max_ask >= targetProfit) {
					/*
					 * 判断是否达到回撤比
					 */
					if(profit_ask * 100.00 / profit_max_ask < backProfitPercent) {
						//平仓
						int size = askGrids.getAllOrders().size();
						askGrids.closeAndRemoveOrder(askGrids.getAllOrders());
						profit_max_ask = 0.0;
						log("Take profit(ask), profit: " + profit_ask + ", order count: " + size);
					}
				} else {
					/*
					 * 判断是否达到止损额
					 */
					if(profit_ask < stopLoss) {
						//平仓
						int size = askGrids.getAllOrders().size();
						askGrids.closeAndRemoveOrder(askGrids.getAllOrders());
						profit_max_ask = 0.0;
						log("Stop loss(ask), loss: " + profit_ask + ", order count: " + size);
					}
				}
				
				//bid
				if(profit_max_bid >= targetProfit) {
					/*
					 * 判断是否达到回撤比
					 */
					if(profit_bid * 100.00 / profit_max_bid < backProfitPercent) {
						//平仓
						int size = bidGrids.getAllOrders().size();
						bidGrids.closeAndRemoveOrder(bidGrids.getAllOrders());
						profit_max_bid = 0.0;
						log("Take profit(bid), profit: " + profit_bid + ", order count: " + size);
					}
				} else {
					/*
					 * 判断是否达到止损额
					 */
					if(profit_bid < stopLoss) {
						//平仓
						int size = bidGrids.getAllOrders().size();
						bidGrids.closeAndRemoveOrder(bidGrids.getAllOrders());
						profit_max_bid = 0.0;
						log("Stop loss(bid), loss: " + profit_bid + ", order count: " + size);
					}
				}
			
				askGrids.offer(ask);
				bidGrids.offer(bid);
			}
		}
	}
	
	/**
	 * 卖价格子事件(Ask grid change)
	 */
	GridCallback askGridsCallback = new GridCallback() {
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
				IOrder maxNormalOrder = getOrder(askGrids, OrderType.Normal, true);
				if(!existOrder(enter, OrderType.Normal) && (maxNormalOrder == null || enter.getStart() > maxNormalOrder.getOpenPrice())) {
					// normal order
					submitOrder(enter, OrderType.Normal, IEngine.OrderCommand.BUY, lots);
				}

			}
			// down
			else {}

		}
	};

	/**
	 * 买价格子事件(Bid grid change)
	 */
	GridCallback bidGridsCallback = new GridCallback() {
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
				IOrder minNormalOrder = getOrder(bidGrids, OrderType.Normal, false);
				if(!existOrder(enter, OrderType.Normal) && (minNormalOrder == null || enter.getStart() < minNormalOrder.getOpenPrice())) {
					// normal order
					submitOrder(enter, OrderType.Normal, IEngine.OrderCommand.SELL, lots);
				}
				
			}
			// up
			else {}
			
		}
	};

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
	 * 提交订单
	 * @param grid
	 * @param type
	 * @param command
	 * @param lots
	 */
	public void submitOrder(Grid grid, OrderType type, OrderCommand command, double lots) {
		String orderLabel = "SuperIvan_Order_" + command + "_" + type + "_" + (grid.getOffset() < 0 ? "n" + (-grid.getOffset()) : grid.getOffset());
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
