package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * SuperMartin
 * 追随趋势, 反马丁策略
 * @author liteng
 *
 */
public class AntiMartin implements IStrategy {

	public static final int ORDER_TIMEOUT = 2000;
	public static final SimpleDateFormat dateFormart = new SimpleDateFormat("yyyyMMdd_HHmmss");

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("马丁单间隔")
	public Integer gap_martin = 80;

	@Configurable("基础手数")
	public double lots = 0.001;

	@Configurable("马丁倍数")
	public double martin_multiple = 2;
	
	@Configurable("马丁连续次数")
	public int martin_times = 8;
	
	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;

	GridSystem gridsMartinAsk = null, gridsMartinBid = null;
	
	@Override
	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		indicators = context.getIndicators();
		chart = context.getChart(instrument);
	}
	
	/**
	 * 卖价格子事件(Ask grid change)
	 */
	GridCallback gridsMartinCallbackAsk = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {
			
			// first grid
			if(leave == null) return;
		
			// up
			if(leave.getOffset() < enter.getOffset()) {}
			// down
			else {}
			
		}
	};
	
	/**
	 * 买价格子事件(Bid grid change)
	 */
	GridCallback gridsMartinCallbackBid = new GridCallback() {
		int times = 0;
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {
			
			// first grid
			if(leave == null) return;
			
			IOrder order = null;
			List<IOrder> orders = gridsMartinBid.getAllOrders();
			if(orders.size() > 0) {
				order = orders.get(0);
			}
			
			// down
			if(leave.getOffset() > enter.getOffset()) {
				if(order == null) {
					submitOrder(enter, OrderType.Martin, OrderCommand.SELL, lots);
					times = 1;
				} else {
					// right
					if(order.getOrderCommand().equals(OrderCommand.SELL)) {
						if(enter.contains(order)) return;
						// close & submit double
						gridsMartinBid.closeAndRemoveOrder(order);
						if(times < martin_times) {
							submitOrder(enter, OrderType.Martin, OrderCommand.SELL, order.getAmount() * martin_multiple);
							times++;
						} else {
							log("============== Martin!!! ==============");
							times = 0;
						}
					}
					// wrong
					else {
						if(leave.contains(order)) return;
						// close
						gridsMartinBid.closeAndRemoveOrder(order);
						times = 0;
					}
				}
			}
			// up
			else {
				if(order == null) {
					submitOrder(enter, OrderType.Martin, OrderCommand.BUY, lots);
					times = 1;
				} else {
					// right
					if(order.getOrderCommand().equals(OrderCommand.BUY)) {
						if(enter.contains(order)) return;
						// close & submit double
						gridsMartinBid.closeAndRemoveOrder(order);
						if(times < martin_times) {
							submitOrder(enter, OrderType.Martin, OrderCommand.BUY, order.getAmount() * martin_multiple);
							times++;	
						} else {
							times = 0;
							log("============== Martin!!! ==============");
						}
					}
					// wrong
					else {
						if(leave.contains(order)) return;
						// close
						gridsMartinBid.closeAndRemoveOrder(order);
						times = 0;
					}
				}
			}
			
		}
	};

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		if(instrument.equals(this.instrument)) {
			double ask = tick.getAsk(), bid = tick.getBid();
			
			// init grid system
			double realPipValue = instrument.getPipValue() * 0.1;
			if(gridsMartinAsk == null && gridsMartinBid == null) {
				/*
				 *  init grid system
				 */
				gridsMartinAsk = new GridSystem("ask_grids", ask, gap_martin * realPipValue, instrument.getPipScale() + 1);
				gridsMartinAsk.setGridCallback(gridsMartinCallbackAsk);
				gridsMartinAsk.setConsole(console);
				log("AskGrid create: " + "ask_grids, " + ask + ", " + gap_martin + ", " + (instrument.getPipScale() + 1));
				gridsMartinBid = new GridSystem("bid_grids", bid, gap_martin * realPipValue, instrument.getPipScale() + 1, chart);
				gridsMartinBid.setGridCallback(gridsMartinCallbackBid);
				gridsMartinBid.setConsole(console);
				log("BidGrid create: " + "bid_grids, " + bid + ", " + gap_martin + ", " + (instrument.getPipScale() + 1));
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
		
		public boolean contains(IOrder order) {
			for (IOrder o : orders) {
				if(o.equals(order)) {
					return true;
				}
			}
			return false;
		}
	}
	
	interface GridCallback {
		public void onGridChange(Grid leave, Grid enter, double price);
	}
}
