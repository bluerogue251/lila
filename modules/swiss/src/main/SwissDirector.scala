package lila.swiss

import chess.{ Black, Color, White }
import org.joda.time.DateTime
import scala.util.chaining._

import lila.db.dsl._
import lila.game.Game

final private class SwissDirector(
    colls: SwissColls,
    pairingSystem: PairingSystem,
    gameRepo: lila.game.GameRepo,
    onStart: Game.ID => Unit
)(implicit
    ec: scala.concurrent.ExecutionContext,
    idGenerator: lila.game.IdGenerator
) {
  import BsonHandlers._

  // sequenced by SwissApi
  private[swiss] def startRound(from: Swiss): Fu[Option[(Swiss, List[SwissPairing])]] =
    pairingSystem(from)
      .flatMap { pendings =>
        if (pendings.isEmpty) fuccess(none) // terminate
        else {
          val swiss = from.startRound
          for {
            players <- SwissPlayer.fields { f =>
              colls.player.ext
                .find($doc(f.swissId -> swiss.id))
                .sort($sort asc f.number)
                .list[SwissPlayer]()
            }
            pairings <- pendings.collect {
              case Right(SwissPairing.Pending(w, b)) =>
                idGenerator.game dmap { id =>
                  SwissPairing(
                    id = id,
                    swissId = swiss.id,
                    round = swiss.round,
                    white = w,
                    black = b,
                    status = Left(SwissPairing.Ongoing)
                  )
                }
            }.sequenceFu
            _ <-
              colls.swiss.update
                .one(
                  $id(swiss.id),
                  $unset("nextRoundAt") ++ $set(
                    "round"     -> swiss.round,
                    "nbOngoing" -> pairings.size
                  )
                )
                .void
            date = DateTime.now
            byes = pendings.collect { case Left(bye) => bye.player }
            _ <- SwissPlayer.fields { f =>
              colls.player.update
                .one($doc(f.number $in byes, f.swissId -> swiss.id), $addToSet(f.byes -> swiss.round))
                .void
            }
            _ <- colls.pairing.insert.many(pairings).void
            games = pairings.map(makeGame(swiss, SwissPlayer.toMap(players)))
            _ <- lila.common.Future.applySequentially(games) { game =>
              gameRepo.insertDenormalized(game) >>- onStart(game.id)
            }
          } yield Some(swiss -> pairings)
        }
      }
      .recover {
        case PairingSystem.BBPairingException(msg, input) =>
          logger.warn(s"BBPairing ${from.id} $msg")
          logger.info(s"BBPairing ${from.id} $input")
          Some(from -> List.empty[SwissPairing])
      }
      .monSuccess(_.swiss.startRound)

  private def makeGame(swiss: Swiss, players: Map[SwissPlayer.Number, SwissPlayer])(
      pairing: SwissPairing
  ): Game =
    Game
      .make(
        chess = chess.Game(
          variantOption = Some(swiss.variant),
          fen = none
        ) pipe { g =>
          val turns = g.player.fold(0, 1)
          g.copy(
            clock = swiss.clock.toClock.some,
            turns = turns,
            startedAtTurn = turns
          )
        },
        whitePlayer = makePlayer(White, players get pairing.white err s"Missing pairing white $pairing"),
        blackPlayer = makePlayer(Black, players get pairing.black err s"Missing pairing black $pairing"),
        mode = chess.Mode(swiss.settings.rated),
        source = lila.game.Source.Swiss,
        pgnImport = None
      )
      .withId(pairing.gameId)
      .withSwissId(swiss.id.value)
      .start

  private def makePlayer(color: Color, player: SwissPlayer) =
    lila.game.Player.make(color, player.userId, player.rating, player.provisional)
}

//   private object SwissDirector {

//     case class Result(swiss: Swiss, playerMap: SwissPlayer
