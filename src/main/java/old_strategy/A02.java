package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class A02 implements IStrategy {
	
	public static final int ORDER_TIMEOUT = 2000;

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("间隔点数(point)")
	public Integer gap = 50;
	
	@Configurable("回撤下单数")
	public Integer martinLimit = 3;

	@Configurable("基础手数")
	public double lots = 0.001;
	
	
	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;

	GridSystem askGrids = null;
	GridSystem bidGrids = null;

	// continuous change grids
	int continuous_up_grids = 0, continuous_down_grids = 0;
	
	// timing
	long timing = 0;
	
	// trading time
	boolean isTradingTime = false;
	
	@Override
	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		indicators = context.getIndicators();
		chart = context.getChart(instrument);
		
		/*
		 * get last tick
		 */
		ITick lastTick = history.getLastTick(instrument);
		while(lastTick == null) {
			log("retrieve last tick");
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			lastTick = history.getLastTick(instrument);
		}
		
		/*
		 * init grid system
		 */
		askGrids = new GridSystem("ask_gridsystem", lastTick.getAsk(), gap * instrument.getPipValue() * 0.1, instrument.getPipScale() + 1);
		askGrids.setConsole(console);
		askGrids.setGridCallback(new GridCallback() {
			@Override
			public void onGridChange(Grid leave, Grid enter, double price) {
				if(leave == null) return;
				
				/*
				 * close martin orders
				 */
				List<IOrder> martinOrders = getOrders(askGrids, OrderType.Martin);
				if(martinOrders.size() > 0) {
					if(enter.getStart() > getOrder(askGrids, OrderType.Martin, true).getOpenPrice()) {
						askGrids.closeAndRemoveOrder(martinOrders);
					}
				}
				
				/*
				 * up
				 */
				if(leave.getOffset() < enter.getOffset()) {
					continuous_up_grids++;
					
					/*
					 * 趋势单
					 * 条件：连续上升2格以上 且 进入的格子没有趋势单
					 */
					if(continuous_up_grids >= 2 && !existOrder(enter, OrderType.Normal)) {
						submitOrder(enter, OrderType.Normal, OrderCommand.BUY, lots);
					}
				}
				/*
				 * down
				 */
				else {
					continuous_up_grids = 0;
					
					/*
					 * 马丁单
					 * 条件：不存在马丁单 或 (离开的格子有马丁单 且 进入的格子没有马丁单)
					 */
					int martinCount = martinOrders.size();
					if(martinCount == 0 || (existOrder(leave, OrderType.Martin) && !existOrder(enter, OrderType.Martin))) {
						//
						if(martinCount < martinLimit) {
							double martinLots = lots * (martinCount + 1);
							submitOrder(enter, OrderType.Martin, OrderCommand.BUY, martinLots);
						}
					}
				}
			}
		});

		bidGrids = new GridSystem("bid_gridsystem", lastTick.getBid(), gap * instrument.getPipValue() * 0.1, instrument.getPipScale() + 1);
		bidGrids.setConsole(console);
		bidGrids.setGridCallback(new GridCallback() {
			@Override
			public void onGridChange(Grid leave, Grid enter, double price) {
				if(leave == null) return;
				
				/*
				 * close martin orders
				 */
				List<IOrder> martinOrders = getOrders(bidGrids, OrderType.Martin);
				if(martinOrders.size() > 0) {
					if(enter.getStart() < getOrder(bidGrids, OrderType.Martin, false).getOpenPrice()) {
						bidGrids.closeAndRemoveOrder(martinOrders);
					}
				}
				
				/*
				 * up
				 */
				if(leave.getOffset() < enter.getOffset()) {
					continuous_down_grids = 0;
					
					/*
					 * 马丁单
					 * 条件：不存在马丁单 或 (离开的格子有马丁单 且 进入的格子没有马丁单)
					 */
					int martinCount = martinOrders.size();
					if(martinCount == 0 || (existOrder(leave, OrderType.Martin) && !existOrder(enter, OrderType.Martin))) {
						//
						if(martinCount < martinLimit) {
							double martinLots = lots * (martinCount + 1);
							submitOrder(enter, OrderType.Martin, OrderCommand.SELL, martinLots);
						}
					}
				}
				/*
				 * down
				 */
				else {
					continuous_down_grids++;
					
					/*
					 * 趋势单
					 * 条件：连续下降2格以上 且 进入的格子没有趋势单
					 */
					if(continuous_down_grids >= 2 && !existOrder(enter, OrderType.Normal)) {
						submitOrder(enter, OrderType.Normal, OrderCommand.SELL, lots);
					}
				}
			}
		});
		
		// init timing
		timing = lastTick.getTime();
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		
		if(instrument.equals(this.instrument)) {
			
			/*
			 * 交易时间进入非交易时间, 订单全平
			 */
			if(isTradingTime && !isTradingTime(tick.getTime())) {
				//订单全平
				log("==== close all / non-trading time");
				askGrids.closeAndRemoveOrder(askGrids.getAllOrders());
				bidGrids.closeAndRemoveOrder(bidGrids.getAllOrders());
			}
			
			isTradingTime = isTradingTime(tick.getTime());
			if(!isTradingTime) return;
			
			/*
			 * timing / 5 hours
			 */
			if((tick.getTime() - timing) > TimeUnit.HOURS.toMillis(5)) {
				timing = tick.getTime();
				log("==== close all / 5 hours ====");
				askGrids.closeAndRemoveOrder(askGrids.getAllOrders());
				bidGrids.closeAndRemoveOrder(bidGrids.getAllOrders());
			}
			
			/*
			 * offer tick
			 */
			if(askGrids != null) {
				askGrids.offer(tick.getAsk());
			}
			if(bidGrids != null) {
				bidGrids.offer(tick.getBid());
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
	
	public boolean isTradingTime(long time) {
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(time);
		int day_of_week = cal.get(Calendar.DAY_OF_WEEK);
		int hour_of_day = cal.get(Calendar.HOUR_OF_DAY);
		
		//周六全天不交易
		if(day_of_week == Calendar.SATURDAY) {
			return false;
		}
		
		//周五18:00点后不交易
		if(day_of_week == Calendar.FRIDAY && hour_of_day >= 18) {
			return false;
		}
		
		//周日22:00前不交易
		if(day_of_week == Calendar.SUNDAY && hour_of_day < 22) {
			return false;
		}
		
		return true;
	}
	
	public void log(String... str) {
		StringBuilder sb = new StringBuilder();
		for (String s : str) {
			sb.append(s);
		}
		console.getOut().println(sb.toString());
	}
	
	public void submitOrder(Grid grid, OrderType type, OrderCommand command, double lots) {
		String orderLabel = "A02_Order_" + command + "_" + type + "_" + (grid.getOffset() < 0 ? "n" + (-grid.getOffset()) : grid.getOffset());
		try {
			IOrder order = engine.submitOrder(
					orderLabel,
					instrument,
					command,
					lots);
			if(command.equals(OrderCommand.BUY)) {
				order.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
			}
			console.getOut().println("[Order][Open] " + orderLabel + ", price: " + order.getOpenPrice() + ", lots: " + lots);
			grid.getOrders().add(order);
		} catch (JFException e) {
			e.printStackTrace();
			log("[ERROR] submit order error: " + e.getLocalizedMessage());
		}
	}
	
	public void closeOrder(Grid grid, IOrder order) {
		try {
			order.close();
			order.waitForUpdate(ORDER_TIMEOUT, State.CLOSED);
			console.getOut().println("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
			grid.getOrders().remove(order);
		} catch (JFException e) {
			e.printStackTrace();
			log("[ERROR] close order error: " + e.getLocalizedMessage());
		}
	}
	
	/**
	 * 平掉所有马丁单
	 * @param gridSystem
	 */
	private void closeAllOrder(GridSystem gridSystem, OrderType type) {
		List<Grid> gridsAbove = gridSystem.getGridsAbove();
		for (Grid grid : gridsAbove) {
			closeAllOrder(grid, type);
		}

		List<Grid> gridsUnder = gridSystem.getGridsUnder();
		for (Grid grid : gridsUnder) {
			closeAllOrder(grid, type);
		}
		
//		console.getOut().println("[Order] close all martin order (" + gridSystem.getName() + ")!");
	}
	
	/**
	 * 平掉所有马丁单
	 * @param grid
	 */
	private void closeAllOrder(Grid grid, OrderType type) {
		List<IOrder> orders = grid.getOrders();
		for(int i = orders.size() - 1; i >= 0; i--) {
			IOrder order = orders.get(i);
			if(isOrder(order, type)) {
				try {
					order.close();
					order.waitForUpdate(ORDER_TIMEOUT, State.CLOSED);
					console.getOut().println("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
					orders.remove(order);
				} catch (JFException e) {
					console.getOut().println("[ERROR] close martin order error: " + e.getLocalizedMessage());
					e.printStackTrace();
				}
			}
		}
	}
	
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
	 * 获取最大or最小价格的order
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
	 * 获得所有马丁单
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
	
	public double countOrdersLots(GridSystem gridSystem, OrderType type) {
		double count = 0;
		List<IOrder> orders = getOrders(gridSystem, type);
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