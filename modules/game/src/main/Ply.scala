package lila.game

case class Ply(value: Int)

object Ply {
  def next(game: Game): Ply = Ply(game.turns + 1)
  def next(pov: Pov): Ply = next(pov.game)
}
