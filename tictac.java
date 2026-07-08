// Imports all classes from the java.util package, including List and ArrayList used later.
import java.util.*;

// Defines a fixed set of constants representing the possible states of a single cell.
enum Symbol { X, O, EMPTY }

// Defines a fixed set of constants representing the overall status of the match.
enum GameState { PLAYING, X_WIN, O_WIN, DRAW }

// Represents a single square/position on the game board.
class Cell {
    // Stores the row index, column index, and the current mark (X, O, or EMPTY) in this cell.
    int row, col; Symbol symbol;
    
    // Constructor: initializes the cell with its specific grid coordinates and defaults it to EMPTY.
    Cell(int r, int c) { row = r; col = c; symbol = Symbol.EMPTY; }
    
    // Helper method that returns true if no player has placed a symbol in this cell yet.
    boolean isEmpty() { return symbol == Symbol.EMPTY; }
}

// Represents the physical playing grid and manages its state.
class Board {
    // Stores the dimension of the board (e.g., 3 for 3x3) and the 2D array of Cell objects.
    int size; Cell[][] grid;
    
    // Keeps track of how many total moves have been made to easily check for a draw.
    int moveCount = 0;
    
    // Constructor: sets the board size and allocates memory for the 2D grid array.
    Board(int n) {
        size = n; grid = new Cell[n][n];
        // Nested loops to iterate through every row and column, creating a new Cell object for each slot.
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) grid[i][j] = new Cell(i, j);
    }
    
    // Attempts to place a player's symbol at the specified row and column.
    boolean place(int r, int c, Symbol s) {
        // Validates the move: fails if coordinates are out of bounds or if the cell is already occupied.
        if (r < 0 || r >= size || c < 0 || c >= size || !grid[r][c].isEmpty()) return false;
        
        // Updates the cell's symbol to the player's symbol and increments the total move counter.
        grid[r][c].symbol = s; moveCount++;
        
        // Returns true indicating the move was successfully placed.
        return true;
    }
    
    // Checks if the board is completely filled by comparing total moves to total possible cells (size * size).
    boolean isFull() { return moveCount == size * size; }
}

// Strategy Pattern Interface: Defines a contract for different ways to calculate a win condition.
interface WinStrategy { boolean checkWin(Board board, int row, int col, Symbol symbol); }

// A concrete implementation of WinStrategy for standard Tic-Tac-Toe rules.
class StandardWinChecker implements WinStrategy {
    // Implements the logic to check if the last move resulted in a win.
    public boolean checkWin(Board board, int row, int col, Symbol symbol) {
        // Gets the dimension of the board (e.g., 3).
        int n = board.size;
        
        // Start by assuming the player won the horizontal row.
        boolean win = true;
        // Check every cell in the current row; if any cell doesn't match the player's symbol, it's not a win.
        for (int c = 0; c < n; c++) if (board.grid[row][c].symbol != symbol) { win = false; break; }
        // If the horizontal assumption remained true, the player won.
        if (win) return true;
        
        // Reset assumption and check the vertical column.
        win = true;
        // Check every cell in the current column; break if a mismatch is found.
        for (int r = 0; r < n; r++) if (board.grid[r][col].symbol != symbol) { win = false; break; }
        // If the vertical assumption remained true, the player won.
        if (win) return true;
        
        // Check the main diagonal (top-left to bottom-right), but only if the move was actually on this diagonal (row == col).
        if (row == col) {
            win = true;
            // Iterate through the main diagonal cells (0,0), (1,1), (2,2), etc.
            for (int i = 0; i < n; i++) if (board.grid[i][i].symbol != symbol) { win = false; break; }
            if (win) return true;
        }
        
        // Check the anti-diagonal (top-right to bottom-left), but only if the move falls on it (row + col == size - 1).
        if (row + col == n - 1) {
            win = true;
            // Iterate through the anti-diagonal cells.
            for (int i = 0; i < n; i++) if (board.grid[i][n-1-i].symbol != symbol) { win = false; break; }
            if (win) return true;
        }
        
        // If all checks fail, the move did not result in a win.
        return false;
    }
}

// Observer Pattern Interface: Defines a contract for objects that want to be notified of game state changes.
interface GameObserver { void onStateChange(GameState state, Player player); }

// Represents a person playing the game.
class Player {
    // Stores the player's display name and their assigned symbol (X or O).
    String name; Symbol symbol;
    
    // Constructor to initialize the player's name and symbol.
    Player(String name, Symbol symbol) { this.name = name; this.symbol = symbol; }
}

// The main class that orchestrates the rules, players, board, and game flow.
class Game {
    // The game components: the board, an array of the two players, and an index tracking whose turn it is (0 or 1).
    Board board; Player[] players; int currentPlayer = 0;
    
    // Sets the initial game state to actively PLAYING.
    GameState state = GameState.PLAYING;
    
    // The specific rule set being used to determine a winner.
    WinStrategy winChecker;
    
    // A list of external observers (like a UI or console logger) listening for state changes.
    List<GameObserver> observers = new ArrayList<>();

    // Constructor: initializes the game with a specific board size, two players, and a win-checking strategy.
    Game(int size, Player p1, Player p2, WinStrategy strategy) {
        board = new Board(size); players = new Player[]{p1, p2}; winChecker = strategy;
    }

    // Allows external objects to subscribe to game events.
    void addObserver(GameObserver o) { observers.add(o); }
    
    // Loops through all subscribed observers and triggers their onStateChange method with the current state and player.
    void notifyObservers() { observers.forEach(o -> o.onStateChange(state, players[currentPlayer])); }

    // Core method to execute a player's turn at the given coordinates.
    boolean makeMove(int row, int col) {
        // If the game is already won or drawn, no more moves can be made.
        if (state != GameState.PLAYING) return false;
        
        // Retrieves the player whose turn it currently is.
        Player player = players[currentPlayer];
        
        // Attempts to place the symbol on the board; if the move is invalid (e.g., cell taken), return false.
        if (!board.place(row, col, player.symbol)) return false;
        
        // Checks if this valid move resulted in a win using the injected WinStrategy.
        if (winChecker.checkWin(board, row, col, player.symbol)) {
            // Update the game state based on which symbol won.
            state = player.symbol == Symbol.X ? GameState.X_WIN : GameState.O_WIN;
            // Alert any listeners that the game has ended with a win, and return true (successful move).
            notifyObservers(); return true;
        }
        
        // If there is no win, check if the board is completely full resulting in a tie.
        if (board.isFull()) {
            // Update state to DRAW, notify listeners, and return true.
            state = GameState.DRAW; notifyObservers(); return true;
        }
        
        // If the game is still playing, flip the currentPlayer index (from 0 to 1, or 1 to 0) for the next turn.
        currentPlayer = 1 - currentPlayer;
        
        // Return true indicating the move was valid and executed.
        return true;
    }

    // A simple text-based rendering of the current board state to the console.
    void printBoard() {
        // Iterate through rows.
        for (int i = 0; i < board.size; i++) {
            // Iterate through columns.
            for (int j = 0; j < board.size; j++) {
                // Get the symbol at the current cell.
                Symbol s = board.grid[i][j].symbol;
                // Print a dot if empty, otherwise print the X or O, followed by a space.
                System.out.print((s == Symbol.EMPTY ? "." : s) + " ");
            }
            // Move to the next line after finishing a row.
            System.out.println();
        }
    }
}

// Usage:
// Player p1 = new Player("Alice", Symbol.X), p2 = new Player("Bob", Symbol.O); // Create two players.
// Game game = new Game(3, p1, p2, new StandardWinChecker()); // Initialize a 3x3 game with standard rules.
// game.addObserver((s, p) -> System.out.println("State: " + s + " by " + p.name)); // Add a listener to print state changes.
// game.makeMove(0,0); game.makeMove(1,0); // Alice plays top-left, Bob plays middle-left.
// game.makeMove(0,1); game.makeMove(1,1); // Alice plays top-middle, Bob plays center.
// game.makeMove(0,2); // Alice plays top-right, triggering a win condition. The observer prints the state change.