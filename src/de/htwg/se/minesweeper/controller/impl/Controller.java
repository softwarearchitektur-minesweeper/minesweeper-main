package de.htwg.se.minesweeper.controller.impl;

import de.htwg.se.minesweeper.controller.IController;
import de.htwg.se.minesweeper.designpattern.observer.Observable;
import de.htwg.se.minesweeper.model.Cell;
import de.htwg.se.minesweeper.model.Grid;
import de.htwg.se.minesweeper.persistence.DAOFactory;
import de.htwg.se.minesweeper.persistence.IGridDao;
import de.htwg.se.minesweeper.persistence.couchdb.GridCouchdbDAO;
import de.htwg.se.minesweeper.persistence.db4o.GridDb4oDAO;
import de.htwg.se.minesweeper.persistence.hibernate.GridHibernateDAO;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Niels Boecker
 * @author Mark Unger
 * @author Aiham Abousaleh
 */
public class Controller extends Observable implements IController {

	private static final String DEFAULT_DIFFICULTY = "intermediate";
	private static final String DEFAULT_SIZE = "small";
	private Grid grid;
	private State state;

	// for time measuring
	private long timeOfGameStartMills;
	private long elapsedTimeSeconds;
	// private DAOFactory FACTORY;

	private IGridDao dao;

	private void db4o() throws IOException {
		DAOFactory.getDAOFactory(DAOFactory.DB4O);
		dao = new GridDb4oDAO();

	}

	private void couchDB() throws IOException {
		DAOFactory.getDAOFactory(DAOFactory.CouchDB);
		dao = new GridCouchdbDAO();

	}


	private void hibernate() throws IOException {
		DAOFactory.getDAOFactory(DAOFactory.Hibernate);
		dao = new GridHibernateDAO();

	}
	public Controller() throws IOException {
		 //db4o();
		 //couchDB();
		hibernate();
		startNewGame();

	}

	@Override
	public void quit() {
		System.exit(0);
	}

	@Override
	public void startNewGame() {
		startNewGame(DEFAULT_SIZE, DEFAULT_DIFFICULTY);
	}

	@Override
	public void startNewGame(String gridSize, String difficulty) {
		int numberOfRowsAndCols;
		int numberOfMines;

		switch (gridSize) {
		case "large":
			numberOfRowsAndCols = 17;
			break;
		case "small":
			numberOfRowsAndCols = 7;
			break;
		case "medium":
		default:
			numberOfRowsAndCols = 12;
		}

		switch (difficulty) {
		case "expert":
			numberOfMines = (int) 2.0 * numberOfRowsAndCols;
			break;
		case "beginner":
			numberOfMines = (int) (0.8 * numberOfRowsAndCols);
			break;
		case "intermediate":
		default:
			numberOfMines = (int) (1.5 * numberOfRowsAndCols);
		}

		startNewGame(numberOfRowsAndCols, numberOfMines);
	}

	@Override
	public void startNewGame(int numberOfRowsAndCols, int numberOfMines) {
		try {
			// TODO this tow lines can be called from GUI (either new Grid or
			// load from DB)
			// this.grid = new Grid(numberOfRowsAndCols, numberOfRowsAndCols, numberOfMines);
			 this.grid = loadDB();
			this.state = State.NEW_GAME;
			this.timeOfGameStartMills = System.currentTimeMillis();
			notifyObservers();
		} catch (Exception e) {
			state = State.ERROR;
		}
	}

	private Grid loadDB() {

		List<Grid> allGrids = dao.getAllGrids();
//		for (Grid grid : allGrids) {
			Grid g = allGrids.get(0);
		
			return dao.getGridById(g.getId());

	//	}
	//	return null;

	}

	@Override
	public void commitNewSettingsAndRestart(int numberOfRowsAndCols, int numberOfMines) {
		startNewGame(numberOfRowsAndCols, numberOfMines);
		state = State.CHANGE_SETTINGS_SUCCESS;
		notifyObservers();
	}

	@Override
	public void revealCell(int row, int col) {
		revealCell(this.grid.getCellAt(row, col));

		dao.saveOrUpdateGrid(this.grid);

	}

	@Override
	public void revealCell(Cell cell) {
		// ignore if game is not running
		if (getState() == State.GAME_LOST || getState() == State.GAME_WON)
			return;

		this.state = State.REVEAL_CELL;

		// potentially recursive revealing, or winning / losing
		recursiveRevealCell(cell);

		// notify observers only once
		notifyObservers();

	}

	/**
	 * Use this inner method for the recursive calls to prevent notifying
	 * observers after each reveal.
	 */
	@Override
	public void recursiveRevealCell(Cell cell) {
		// ignore if cell is revealed
		if (cell == null || cell.isRevealed())
			return;

		// never explode in first round
		if (isFirstRound() && cell.hasMine()) {
			System.err.println("Prevented first round explosion.");
			this.grid = new Grid(this.grid.getNumberOfRows(), this.grid.getNumberOfColumns(),
					this.grid.getNumberOfMines());
			Cell cellAtSamePosition = this.grid.getCellAt(cell.getPosition());
			recursiveRevealCell(cellAtSamePosition);
			return;
		}

		// reveal cell
		cell.setRevealed(true);

		// check if lost
		if (cell.hasMine()) {
			setElapsedTime();
			this.state = State.GAME_LOST; // notifyObservers handled by wrapping
											// revealCell() method
			return;
		}

		// check if won
		if (allCellsAreRevealed()) {
			setElapsedTime();
			this.state = State.GAME_WON; // notifyObservers handled by wrapping
											// revealCell() method
			return;
		}

		// check if we can propagate revealing
		if (cell.getSurroundingMines() == 0) {
			final List<Cell> neighbors = this.grid.getAllNeighbors(cell);
			for (Cell neighbor : neighbors) {
				recursiveRevealCell(neighbor);
			}
		}

	}

	@Override
	public boolean isFirstRound() {
		return grid.getNumberOfRevealedCells() == 0;
	}

	private void setElapsedTime() {
		long elapsedTimeNanos = System.nanoTime() - timeOfGameStartMills;
		elapsedTimeSeconds = TimeUnit.SECONDS.convert(elapsedTimeNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void toggleFlag(int row, int col) {
		final Cell cell = this.grid.getCellAt(row, col);

		if (cell == null || getState() == State.GAME_LOST || getState() == State.GAME_WON)
			return;

		cell.setFlagged(!cell.isFlagged());
		setStateAndNotifyObservers(State.TOGGLE_FLAG);
	}

	@Override
	public boolean allCellsAreRevealed() {
		return grid.getTotalNumberOfCells() == grid.getNumberOfRevealedCells() + grid.getNumberOfMines();
	}

	/**
	 * If a game is currently running, notify observers. Else, start new game
	 * with standard values.
	 */
	@Override
	public void touch() {
		if (this.grid != null)
			notifyObservers();
		else
			startNewGame();
	}

	@Override
	public void setStateAndNotifyObservers(State state) {
		this.state = state;
		notifyObservers();
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public String getHelpText() {
		return "(TUI:n) GUI: Menu	->	New Game: 	This command starts a new game. (reset)\n"
				+ "(TUI:q) GUI: Menu	->	Quit:		This command ends the Game and close it\n"
				+ "(TUI:c) GUI: Menu	->	Settings:	This command sets the number for column/row and mines\n"
				+ "(TUI:h) GUI: ?	    ->	Help:		This command shows the help text";
	}

	@Override
	public Grid getGrid() {
		return grid;
	}

	@Override
	public long getElapsedTimeSeconds() {
		return elapsedTimeSeconds;
	}
}
