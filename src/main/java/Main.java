import lombok.Getter;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

public class Main {
  public static void main(String[] args) {

    // Check if a file name/parameter has been provided
    if (args.length != 1) {
      System.out.println(
          "Not right amount of parameters: Need 1\nUsage:\n java -jar TRV_FESimulator-ver1 <input-file>");
      return;
    }

    // Use String builder to store output reducing number of buffered writer calls
    StringBuilder output = new StringBuilder();

    // Hashmap to store distinct orderBooks
    HashMap<Integer, OrderBook> orderBooks = new HashMap<>();

    // Input file name to read
    String inputFileName = args[0];


    // Use buffered reader to read input file line by line
    try (BufferedReader br = new BufferedReader(new FileReader(inputFileName))) {

      String line = br.readLine();
      while (line != null) {
        // extract information based on comma separations
        String[] information = line.trim().split(",");

        // first value corresponds to the action taken "O" for Enter order and "X" for cancel order
        if (information[0].equals("O")) {
          // process all inputs
          int clientId = Integer.parseInt(information[1].trim().split(" ")[1]);
          Integer orderBookId = Integer.parseInt(information[2].trim().split(" ")[1]);
          // check if order book exists
          if (!orderBooks.containsKey(orderBookId)) {
            orderBooks.put(orderBookId, new OrderBook(output));
          }
          long orderToken = Long.parseLong(information[3].trim().split(" ")[1]);
          String direction = information[4].trim();
          int quantity = Integer.parseInt(information[5].trim().split(" ")[0]);
          int price = Integer.parseInt(information[6].trim().split(" ")[0]);

          // type of order depends on direction
          if (direction.equals("B")) {
            // Is a Buy/Bid
            Bid newBid = new Bid(clientId, orderToken, quantity, price, LocalDateTime.now(), direction);
            orderBooks.get(orderBookId).addBid(newBid);
          } else if (direction.equals("S")) {
            // Is a Sell/Ask
            Ask newAsk = new Ask(clientId, orderToken, quantity, price, LocalDateTime.now(), direction);
            orderBooks.get(orderBookId).addAsk(newAsk);
          }
        } else if (information[0].equals("X")) {
          int clientId = Integer.parseInt(information[1].trim().split(" ")[1]);
          long orderToken = Long.parseLong(information[2].trim().split(" ")[1]);
          // orderBook of order not given so go through every book and try to cancel
          for (OrderBook orderBook : orderBooks.values()) {
            orderBook.cancelOrder(clientId, orderToken);
          }
          // to improve should introduce new classes to store more information, because this will not scale well
        }
        line = br.readLine();
      }
      output.append("\n");

      // add to output the current state of the order books
      for (OrderBook orderBook : orderBooks.values()) {
        if (!orderBook.getOrders().isEmpty()) {
          for (Order order : orderBook.getOrders()) {
            output.append(order.getStatusMessage());
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String outputFileName = inputFileName.split("\\.")[0] + "-output.txt";
    // Use buffered writer to write string builder from earlier, it wouldn't be hard to implement it so
    // that it outputs at the same time instead of at the end
    try (
        BufferedWriter bw = new BufferedWriter(new FileWriter(outputFileName))) {
      bw.write(output.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

/**
 * OrderBook to keep track of all exchanges in the book.
 * Uses a custom classes and comparators to ensure correct ordering of bids and asks
 */
@Getter class OrderBook {
  private final LinkedHashSet<Order> orders;
  private final PriorityQueue<Bid> bids;
  private final PriorityQueue<Ask> asks;
  private final StringBuilder output;

  public OrderBook(StringBuilder output) {
    orders = new LinkedHashSet<>();
    bids = new PriorityQueue<>(new BidComparator());
    asks = new PriorityQueue<>(new AskComparator());
    this.output = output;
  }

  public void addBid(Bid newBid) {
    // add to output string builder
    acknowledgeOrder(newBid);

    // keep track of total executed and use a hash map to store quantities
    // at specific price points (to reduce number of outputs)
    int totalQuantityExecuted=0;
    LinkedHashMap<Integer, Integer> costQuantityMap = new LinkedHashMap<>();

    // ensures that queue not empty, price matched, and enough quantity to keep trading
    while (!asks.isEmpty() && newBid.getPrice() => asks.peek().getPrice() && totalQuantityExecuted < newBid.getQuantity()) {
      Ask oldAsk = asks.peek();

      // price based on the resting order in the book
      int executePrice = oldAsk.getPrice();

      // quantity dependent on already traded amount
      int executeQuantity = Math.min(newBid.getQuantity()-totalQuantityExecuted, oldAsk.getQuantity());

      // executes on the resting orders first
      output.append(oldAsk.execute(executeQuantity, executePrice));
      totalQuantityExecuted+=executeQuantity;

      // use map to keep track of amount traded at each price point
      if (!costQuantityMap.containsKey(executePrice)) {
        costQuantityMap.put(executePrice, 0);
      }
      costQuantityMap.put(executePrice, costQuantityMap.get(executePrice) + executeQuantity);

      // check if the resting order is completely fulfilled, if so remove it
      if (oldAsk.getQuantity() == 0) {
        orders.remove(asks.remove());
      }
    }

    // finally execute new order
    for (Integer executePrice : costQuantityMap.keySet()) {
      output.append(newBid.execute(costQuantityMap.get(executePrice), executePrice));
    }

    // check if there is anymore quantity available, if not don't need to add to order book
    if (newBid.getQuantity() != 0) {
      bids.add(newBid);
      orders.add(newBid);
    }
  }

  public void addAsk(Ask newAsk) {
    // add to output string builder
    acknowledgeOrder(newAsk);

    // keep track of total executed and use a hash map to store quantities
    // at specific price points (to reduce number of outputs)
    int totalQuantityExecuted=0;
    LinkedHashMap<Integer, Integer> costQuantityMap = new LinkedHashMap<>();

    // ensures that queue not empty, price matched, and enough quantity to keep trading
    while (!bids.isEmpty() && newAsk.getPrice() <= bids.peek().getPrice() && totalQuantityExecuted < newAsk.getQuantity()) {
      Bid oldBid = bids.peek();

      // price based on the resting order in the book
      int executePrice = oldBid.getPrice();

      // quantity dependent on already traded amount
      int executeQuantity = Math.min(oldBid.getQuantity(), newAsk.getQuantity()-totalQuantityExecuted);

      // executes on the resting orders first
      output.append(oldBid.execute(executeQuantity, executePrice));
      totalQuantityExecuted+=executeQuantity;

      // use map to keep track of amount traded at each price point
      if (!costQuantityMap.containsKey(executePrice)) {
        costQuantityMap.put(executePrice, 0);
      }

      // check if the resting order is completely fulfilled, if so remove it
      costQuantityMap.put(executePrice, costQuantityMap.get(executePrice) + executeQuantity);
      if (oldBid.getQuantity() == 0) {
        orders.remove(bids.remove());
      }
    }

    // finally execute new order
    for (Integer executePrice : costQuantityMap.keySet()) {
      output.append(newAsk.execute(costQuantityMap.get(executePrice), executePrice));
    }

    // check if there is anymore quantity available, if not don't need to add to order book
    if (newAsk.getQuantity() != 0) {
      asks.add(newAsk);
      orders.add(newAsk);
    }
  }

  public void acknowledgeOrder(Order order){
    output.append(order.getAddedMessage());
  }


  public void cancelOrder(int clientId, long orderToken){
    Order orderToRemove = new Order(clientId, orderToken, 0,0, null, null);
    if (orders.remove(orderToRemove)){
      // Not sure if it is ask or bid so remove from both
      asks.remove(orderToRemove);
      bids.remove(orderToRemove);
      // should probably only activate if the order was still active
      output.append(orderToRemove.getCancelledMessage());
    }
  }
}

/**
 * Order class to keep track of all details of an Order
 */
@Getter
class Order {
  private final int clientId;
  private final long orderToken;
  private int quantity;
  private final int price;
  private final LocalDateTime orderTime;
  private final String direction;

  public Order(int clientId, long orderToken, int quantity, int price, LocalDateTime orderTime, String direction) {
    this.clientId = clientId;
    this.orderToken = orderToken;
    this.quantity = quantity;
    this.price = price;
    this.orderTime = orderTime;
    this.direction = direction;
  }

  public String getStatusMessage(){
    return "O, Client " + clientId + ", Token " + orderToken + ", " + direction + ", " + quantity + ", " + price + "\n";
  }

  public String getAddedMessage() {
    return "A, Client " + clientId + ", Token " + orderToken + "\n";
  }

  public String getCancelledMessage() {
    return "C, Client " + clientId + ", Token " + orderToken + "\n";
  }

  public String execute(int executeQuantity, int executePrice) {
    quantity -= executeQuantity;
    return "E, Client " + clientId + ", Token " + orderToken + ", " + executeQuantity + ", " + executePrice + "\n";
  }

  @Override
  public int hashCode(){
    int hash = 7;
    hash = 29 * hash + 41*this.clientId;
    hash = 29 * hash + 41*(int) this.orderToken;
    return hash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null) return false;
    if (!(o instanceof Order order)) return false;
    return clientId == order.clientId && orderToken == order.orderToken;
  }
}

class Bid extends Order {
  public Bid(int clientId, long orderToken, int quantity, int price, LocalDateTime orderTime, String direction) {
    super(clientId, orderToken, quantity, price, orderTime, direction);
  }
}

class Ask extends Order {
  public Ask(int clientId, long orderToken, int quantity, int price, LocalDateTime orderTime, String direction) {
    super(clientId, orderToken, quantity, price, orderTime, direction);
  }
}

/**
 * Comparator to order Bids in priority of higher price then earlier time.
 */
class BidComparator implements Comparator<Bid> {
  public int compare(Bid b1, Bid b2) {
    if (b1.getPrice() == b2.getPrice()) {
      if (b1.getOrderTime().isBefore(b2.getOrderTime())) {
        return -1;
      } else if (b1.getOrderTime().isAfter(b2.getOrderTime())) {
        return 1;
      }
      return 0;
    } else if (b1.getPrice() > b2.getPrice()) {
      return -1;
    } else {
      return 1;
    }
  }
}

/**
 * Comparator to order Asks in priority of lower price then earlier time.
 */
class AskComparator implements Comparator<Ask> {
  public int compare(Ask a1, Ask a2) {
    if (a1.getPrice() == a2.getPrice()) {
      if (a1.getOrderTime().isBefore(a2.getOrderTime())) {
        return -1;
      } else if (a1.getOrderTime().isAfter(a2.getOrderTime())) {
        return 1;
      }
      return 0;
    } else if (a1.getPrice() > a2.getPrice()) {
      return 1;
    } else {
      return -1;
    }
  }
}
