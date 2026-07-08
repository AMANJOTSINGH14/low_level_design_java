import java.util.*;

// Common abstraction for anything that "teleports" a player from one cell to another.
// Snakes and Ladders both just map a start cell -> an end cell, so a shared interface
// lets Board treat them polymorphically instead of branching on type.
interface BoardEntity {
    int getStart();
    int getEnd();
}

// A Snake moves the player DOWN the board: head (start) -> tail (end, smaller number).
class Snake implements BoardEntity {
    int head, tail;

    Snake(int head, int tail) {
        this.head = head;
        this.tail = tail;
    }

    public int getStart() { return head; } // where you land to trigger it
    public int getEnd() { return tail; }   // where you end up (lower)
}

// A Ladder moves the player UP the board: bottom (start) -> top (end, larger number).
class Ladder implements BoardEntity {
    int bottom, top;

    Ladder(int bottom, int top) {
        this.bottom = bottom;
        this.top = top;
    }

    public int getStart() { return bottom; } // where you land to trigger it
    public int getEnd() { return top; }      // where you end up (higher)
}

// Encapsulates the randomness of rolling. Kept as its own class so it's easy to
// swap in a fixed/seeded die for testing, or change face count without touching Game.
class Dice {
    int faces;
    Random rng = new Random();

    Dice(int faces) {
        this.faces = faces;
    }

    int roll() {
        return rng.nextInt(faces) + 1; // nextInt(faces) gives [0, faces-1], shift to [1, faces]
    }
}

// Simple data holder for a player's identity and current cell.
class Player {
    String name;
    int position = 0; // starts off the board, at cell 0

    Player(String name) {
        this.name = name;
    }
}

// Observer pattern: lets external code (UI, logger, analytics) react to game
// events without Game needing to know who's listening or why.
interface GameObserver {
    void onEvent(String event, Player player, int detail);
}

// Owns the grid size and the snake/ladder lookup. Doesn't know about players,
// turns, or dice — single responsibility is "given a position, resolve it."
class Board {
    int size;

    // Keyed by the START cell of a snake or ladder, so resolvePosition is O(1).
    Map<Integer, BoardEntity> entities = new HashMap<>();

    Board(int size) {
        this.size = size;
    }

    void addSnake(int head, int tail) {
        entities.put(head, new Snake(head, tail));
    }

    void addLadder(int bottom, int top) {
        entities.put(bottom, new Ladder(bottom, top));
    }

    // If the landed-on cell has a snake or ladder, jump to its end; otherwise stay put.
    int resolvePosition(int pos) {
        BoardEntity e = entities.get(pos);
        return e != null ? e.getEnd() : pos;
    }
}

// Orchestrates the actual game loop: turn order, rolling, moving, win detection,
// and notifying observers. This is the "controller" tying Board/Dice/Player together.
class Game {
    Board board;
    Dice dice;

    // Queue models turn rotation naturally: poll the current player, then
    // re-add them at the back so play cycles through everyone in order.
    Queue<Player> players = new LinkedList<>();

    List<GameObserver> observers = new ArrayList<>();
    boolean gameOver = false;

    Game(int boardSize, int diceFaces) {
        board = new Board(boardSize);
        dice = new Dice(diceFaces);
    }

    void addPlayer(Player p) {
        players.add(p);
    }

    void addObserver(GameObserver o) {
        observers.add(o);
    }

    // Fan out an event to every registered observer (e.g. print a message,
    // update a UI, log stats) without Game caring what they do with it.
    void notify(String event, Player p, int detail) {
        observers.forEach(o -> o.onEvent(event, p, detail));
    }

    // Plays exactly one player's turn and returns the winner if this turn won the game,
    // otherwise null. Kept separate from play() so callers can step through turn-by-turn
    // (useful for UI-driven or testable games, not just an auto-run loop).
    Player playTurn() {
        if (gameOver) return null;

        Player current = players.poll(); // take the player whose turn it is
        int roll = dice.roll();
        int newPos = current.position + roll;

        // Overshoot rule: if the roll would go past the last cell, the move is
        // forfeited and the player keeps their current position for next turn.
        if (newPos > board.size) {
            players.add(current); // put them back at the end of the turn order
            return null;
        }

        int oldPos = newPos;                       // cell landed on before resolving snake/ladder
        newPos = board.resolvePosition(newPos);     // apply snake/ladder jump if any
        current.position = newPos;

        // Compare against oldPos (not the pre-roll position) to correctly detect
        // whether a snake or ladder fired, vs. a plain move with no entity.
        if (newPos < oldPos) notify("snakeBit", current, newPos);
        else if (newPos > oldPos) notify("ladderClimbed", current, newPos);
        else notify("moved", current, newPos);

        if (newPos == board.size) {
            gameOver = true;
            notify("won", current, newPos);
            return current;
        }

        players.add(current); // not a winning move, cycle them to the back
        return null;
    }

    // Convenience driver that runs turns until someone wins.
    Player play() {
        while (!gameOver) {
            Player winner = playTurn();
            if (winner != null) return winner;
        }
        return null;
    }
}

// Usage:
// Game game = new Game(100, 6);
// game.addPlayer(new Player("Alice"));
// game.addPlayer(new Player("Bob"));
// game.board.addSnake(99, 10);
// game.board.addLadder(5, 55);
// game.addObserver((evt, p, d) -> System.out.println(evt + ": " + p.name + " at " + d));
// Player winner = game.play();