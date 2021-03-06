# Bookmaker

## Usage

Bookmaker create an *extended* (cf. below) polyglot opening book from a pgn file. Run

`java -jar bookmaker-1.0-jar-with-dependencies.jar <options>`

Options are:

- `-i <input-file>` where `<input-file>` is a PGN file with games
- `-o <output-file>` where `<output-file>` is the filename of the book that is written
- `-d N` where `N` is the number of halfmoves that should be considered. If `e` is not used, all halfmoves up to `N` will be considered (fixed depth).
- `-e N` where `N` is the number of halfmoves that should be followed from the last known ECO classified position.

I.e. if `-d 40` and `-e 10` are supplied and given a game, halfmoves up to the following depth are considered:

- min(last known ECO classified position + 10, 40)

## Extended Polyglot Format

A quick and dirty hack to extend the polyglot book format to include more relevant information for human users. An entry consists of:

````
uint64 Zobrist Hash
uint16 move
uint32 position count
uint8 white winning percentage
uint8 black winning percentage
uint16 average elo
````
Zobrist hash and move are calculated as in polyglot books.
