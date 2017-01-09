package old_strategy;

import com.dukascopy.api.*;
import com.dukascopy.api.IIndicators.MaType;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.drawings.IChartObjectFactory;
import com.dukascopy.api.drawings.IHorizontalLineChartObject;
import com.dukascopy.api.indicators.IIndicator;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 超级伊万
 * 
 * @author liteng
 *
 */
public class SuperIvanTickStrategy implements IStrategy {
	
	public static final int ORDER_TIMEOUT = 2000;

	@Configurable("货币对")
	public Instrument instrument = Instrument.EURUSD;
	
	@Configurable("间隔点数(point)")
	public Integer gap = 15;

	@Configurable("基础手数")
	public double lots = 0.001;
	
	@Configurable("马丁单总手数/趋势单总手数")
	public double martinLotsDivNormalLots = 1;
	
	@Configurable("马丁单增加倍数")
	public double martinMultiple = 1.5;
	
	@Configurable("均线周期")
	public Period smaPeriod = Period.TICK;
	
	@Configurable("均线周期长度")
	public int smaPeriodTime = 100;

	IEngine engine = null;
	IHistory history = null;
	IConsole console = null;
	IIndicators indicators = null;
	IChart chart = null;

	GridSystem askGrids = null;
	GridSystem bidGrids = null;
	
	int round_max_grid_offset = 0;
	int round_min_grid_offset = 0;
	
	@Override
	public void onStart(IContext context) throws JFException {
		engine = context.getEngine();
		history = context.getHistory();
		console = context.getConsole();
		indicators = context.getIndicators();
		chart = context.getChart(instrument);
		
		if(chart != null) {
			// add indicator(SMA) to chart
			IIndicator ma_indicator = indicators.getIndicator("MA");
			chart.add(ma_indicator, new Object[]{smaPeriodTime, MaType.SMA.ordinal()});
			
		}
		
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		
		if(instrument.equals(this.instrument)) {
			double ask = tick.getAsk(), bid = tick.getBid();
			
			// init grid system
			double realPipValue = instrument.getPipValue() * 0.1;
			if(askGrids == null && bidGrids == null) {
				double sma = sma();
				
				// SMA - 200 < bid < SMA + 200
				if(sma - 200 * realPipValue < bid && bid < sma + 200 * realPipValue) {
					/*
					 *  init grid system
					 */
					askGrids = new GridSystem("ask_grids", ask, gap * realPipValue, instrument.getPipScale() + 1);
					askGrids.setGridCallback(askGridsCallback);
					askGrids.setConsole(console);
					console.getOut().println("AskGrid create: " + "ask_grids, " + ask + ", " + gap + ", " + (instrument.getPipScale() + 1));
					bidGrids = new GridSystem("bid_grids", bid, gap * realPipValue, instrument.getPipScale() + 1);
					bidGrids.setGridCallback(bidGridsCallback);
					bidGrids.setConsole(console);
					console.getOut().println("BidGrid create: " + "bid_grids, " + bid + ", " + gap + ", " + (instrument.getPipScale() + 1));
				}
			}
			
			/*
			 * offer tick
			 */
			if(askGrids != null && bidGrids != null) {
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
			// log
//			console.getOut().println("AskGrid change, ask: " + price + ", leave: " + leave + ", enter: " + enter);
			
			// first grid
			if(leave == null) return;
			
			try {
				double sma = sma();
				// 当前价格在均线之上时
				if(price > sma) {
					// up
					if(leave.getOffset() < enter.getOffset()) {
						/*
						 *  记录均线上最高抵达格子的offset
						 */
						//出新高或进入均线之上
						if(round_max_grid_offset == 0 || enter.getOffset() > round_max_grid_offset) {
							round_max_grid_offset = enter.getOffset();	
							//平对头最亏单
							IOrder maxLossOrder = getMaxLossOrder(bidGrids);
							if(maxLossOrder != null) {
								bidGrids.closeAndRemoveOrder(maxLossOrder);
							}
						}

						/*
						 * 趋势单
						 * 条件: 当前格子没有趋势单 && (最大趋势单为null || 当前价格大于最大趋势单价格)
						 */
						IOrder maxNormalOrder = getOrder(askGrids, OrderType.Normal, true);
						if(!existOrder(enter, OrderType.Normal) && (maxNormalOrder == null || enter.getStart() > maxNormalOrder.getOpenPrice())) {
							// normal order
							try {
								String orderLabel = "SuperIvan_Order_Buy_Normal_" + (enter.getOffset() < 0 ? "n" + (-enter.getOffset()) : enter.getOffset());
								IOrder order = engine.submitOrder(
										orderLabel,
										SuperIvanTickStrategy.this.instrument,
										IEngine.OrderCommand.BUY,
										lots);
								order.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
								console.getOut().println("[Order][Open] " + orderLabel + ", price: " + price + ", lots: " + lots);
								enter.getOrders().add(order);
								
							} catch (JFException e) {
								console.getOut().println("[ERROR] submit order error: " + e.getLocalizedMessage());
								e.printStackTrace();
							}	
						}
						
						/*
						 * 判断是否需要平掉所有马丁单
						 */
						List<IOrder> martinOrders = getOrders(askGrids, OrderType.Martin);
						if(martinOrders.size() > 0) {
							IOrder maxMartinOrder = getOrder(askGrids, OrderType.Martin, true);
							if(leave.getStart() > maxMartinOrder.getOpenPrice()) {
								askGrids.closeAndRemoveOrder(martinOrders);
							}
						}

//						/*
//						 * 判断是否需要平掉所有马丁单
//						 * 条件: 离开的格子有马丁单 && 进入的格子没有马丁单
//						 */
//						if(existMartinOrder(leave) && !existMartinOrder(enter) && getOrder(askGrids, OrderType.Martin, true).equals(getOrder(leave, OrderType.Martin, true))) {
//							closeAllMartinOrder(askGrids);
//						}
					}
					// down
					else {
						/*
						 * 趋势下降时是否要开马丁单
						 * 条件: 无马丁单 || (leave有马丁单 && enter无马丁单)
						 */
						IOrder maxMartinOrder = getOrder(askGrids, OrderType.Martin, true);
						if(maxMartinOrder == null || (existOrder(leave, OrderType.Martin) && !existOrder(enter, OrderType.Martin))) {
							// 马丁单手数根据上一格的马丁单手数决定
							double martin_lots = lots;
							
							// 如果上一格已开马丁单, 则此格以上一格基础计算马丁单手数
							IOrder lastMartinOrder = getOrder(leave, OrderType.Martin, true);
							if(lastMartinOrder != null) {
								martin_lots = lastMartinOrder.getAmount() * martinMultiple;
							}
							
							// 判断是否超过马丁单总手数限制
							double countMartinLots = countOrdersLots(askGrids, OrderType.Martin);
							double countNormalLots = countOrdersLots(askGrids, OrderType.Normal);
							if((countMartinLots + martin_lots) / countNormalLots <= martinLotsDivNormalLots) {
								// 开单
								String orderMartinLabel = "SuperIvan_Order_Buy_Martin_" + (enter.getOffset() < 0 ? "n" + (-enter.getOffset()) : enter.getOffset());
								IOrder martinOrder = engine.submitOrder(
										orderMartinLabel,
										SuperIvanTickStrategy.this.instrument,
										IEngine.OrderCommand.BUY,
										martin_lots);
								martinOrder.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
								console.getOut().println("[Order][Open] " + orderMartinLabel + ", price: " + price + ", lots: " + martin_lots);
								enter.getOrders().add(martinOrder);
							}
							
						}
					}
				} else {
					// 均线下
					round_max_grid_offset = 0;
				}
			} catch (JFException e) {
				console.getOut().println("[ERROR] error: " + e.getLocalizedMessage());
				e.printStackTrace();
			}
			
		}
	};
	
	/**
	 * 买价格子事件(Bid grid change)
	 */
	GridCallback bidGridsCallback = new GridCallback() {
		@Override
		public void onGridChange(Grid leave, Grid enter, double price) {
//			console.getOut().println("BidGrid change, bid: " + price + ", leave: " + leave + ", enter: " + enter);

			// first grid
			if(leave == null) return;

			try {
				double sma = sma();

				// 当前价格在均线之下时
				if(price < sma) {
					// up
					if(leave.getOffset() < enter.getOffset()) {
						/*
						 * 趋势上升时是否要开马丁单
						 * 条件: 无马丁单 || (leave有马丁单 && enter无马丁单)
						 */
						IOrder minMartinOrder = getOrder(bidGrids, OrderType.Martin, false);
						if(minMartinOrder == null || (existOrder(leave, OrderType.Martin) && !existOrder(enter, OrderType.Martin))) {
							// 马丁单手数根据上一格的马丁单手数决定
							double martin_lots = lots;

							// 如果上一格已开马丁单, 则此格以上一格基础计算马丁单手数
							IOrder lastMartinOrder = getOrder(leave, OrderType.Martin, false);
							if(lastMartinOrder != null) {
								martin_lots = lastMartinOrder.getAmount() * martinMultiple;
							}

							// 判断是否超过马丁单总手数限制
							double countMartinLots = countOrdersLots(bidGrids, OrderType.Martin);
							double countNormalLots = countOrdersLots(bidGrids, OrderType.Normal);
							if((countMartinLots + martin_lots) / countNormalLots <= martinLotsDivNormalLots) {
								// 开单
								String orderMartinLabel = "SuperIvan_Order_Sell_Martin_" + (enter.getOffset() < 0 ? "n" + (-enter.getOffset()) : enter.getOffset());
								IOrder martinOrder = engine.submitOrder(
										orderMartinLabel,
										SuperIvanTickStrategy.this.instrument,
										IEngine.OrderCommand.SELL,
										martin_lots);
								martinOrder.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
								console.getOut().println("[Order][Open] " + orderMartinLabel + ", price: " + price + ", lots: " + martin_lots);
								enter.getOrders().add(martinOrder);
							}
						}
					}
					// down
					else {
						/*
						 *  记录均线下最低抵达格子的offset
						 */
						//出新低或进入均线之下
						if(round_min_grid_offset == 0 || enter.getOffset() < round_min_grid_offset) {
							round_min_grid_offset = enter.getOffset();
							//平对头最亏单
							IOrder maxLossOrder = getMaxLossOrder(askGrids);
							if(maxLossOrder != null) {
								askGrids.closeAndRemoveOrder(maxLossOrder);
							}
						}
						
						/*
						 * 趋势单
						 * 条件: 当前格子没有趋势单 && (最小趋势单为null || 当前价格小于最小趋势单价格)
						 */
						IOrder minNormalOrder = getOrder(bidGrids, OrderType.Normal, false);
						if(!existOrder(enter, OrderType.Normal) && (minNormalOrder == null || enter.getStart() < minNormalOrder.getOpenPrice())) {
							// normal order
							try {
								String orderLabel = "SuperIvan_Order_Sell_Normal_" + (enter.getOffset() < 0 ? "n" + (-enter.getOffset()) : enter.getOffset());
								IOrder order = engine.submitOrder(
										orderLabel,
										SuperIvanTickStrategy.this.instrument,
										IEngine.OrderCommand.SELL,
										lots);
								order.waitForUpdate(ORDER_TIMEOUT, State.FILLED);
								console.getOut().println("[Order][Open] " + orderLabel + ", price: " + price + ", lots: " + lots);
								enter.getOrders().add(order);
							} catch (JFException e) {
								console.getOut().println("[ERROR] submit order error: " + e.getLocalizedMessage());
								e.printStackTrace();
							}
						}
						
						/*
						 * 判断是否需要平掉所有马丁单
						 */
						List<IOrder> martinOrders = getOrders(bidGrids, OrderType.Martin);
						if(martinOrders.size() > 0) {
							IOrder minMartinOrder = getOrder(bidGrids, OrderType.Martin, false);
							if(leave.getStart() < minMartinOrder.getOpenPrice()) {
								bidGrids.closeAndRemoveOrder(martinOrders);
							}
						}
						
//						/*
//						 * 判断是否需要平掉所有马丁单
//						 * 条件: 离开的格子有马丁单 && 进入的格子没有马丁单
//						 */
//						if(existMartinOrder(leave) && !existMartinOrder(enter) && getOrder(bidGrids, OrderType.Martin, false).equals(getOrder(leave, OrderType.Martin, false))) {
//							closeAllMartinOrder(bidGrids);
//						}
					
					}
				} else {
					// 均线上
					round_min_grid_offset = 0;
				}
			} catch (JFException e) {
				console.getOut().println("[ERROR] error: " + e.getLocalizedMessage());
				e.printStackTrace();
			}

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
	 * 计算SMA指标
	 * @return
	 * @throws JFException
	 */
	private double sma() throws JFException {
		ITick lastTick = null;
		do {
			lastTick = history.getLastTick(this.instrument);
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		} while (lastTick == null);
		
		double sma = indicators.sma(this.instrument, smaPeriod, OfferSide.BID, IIndicators.AppliedPrice.CLOSE, smaPeriodTime, Filter.WEEKENDS, lastTick.getTime(), lastTick.getTime())[0];

		// 四舍五入
		return new BigDecimal(sma).setScale(this.instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP).doubleValue();
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
	 * 计算指定订单总手数
	 * 
	 * @param gridSystem
	 * @param type
	 * @return
	 */
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
						try {
							order.close();
							order.waitForUpdate(SuperIvanTickStrategy.ORDER_TIMEOUT, State.CLOSED);
							console.getOut().println("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
							orders.remove(order);
						} catch (JFException e) {
							e.printStackTrace();
							console.getOut().println("[ERROR] close order error: " + e.getLocalizedMessage());
						}
					}
				}
			}
	
			for (Grid grid : gridsUnder) {
				List<IOrder> orders = grid.getOrders();
				for (int i = orders.size() - 1; i >= 0; i--) {
					IOrder o = orders.get(i);
					if(o.equals(order)) {
						try {
							order.close();
							order.waitForUpdate(SuperIvanTickStrategy.ORDER_TIMEOUT, State.CLOSED);
							console.getOut().println("[Order][Close] " + order.getLabel() + ", close: " + order.getClosePrice() + ", profit: " + order.getProfitLossInAccountCurrency());
							orders.remove(order);
						} catch (JFException e) {
							e.printStackTrace();
							console.getOut().println("[ERROR] close order error: " + e.getLocalizedMessage());
						}
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