# Fortune RPC response compatibility

`/bridge/rpc` authenticates the game instance and checks real-player admission before executing a DailyFortunes operation. A successful response retains the versioned `result` object, and mirrors its business fields at the top level for DailyFortunes 0.2.0, which reads `record`, `records`, `today` and `settings` directly. Both locations must describe exactly the same committed operation; serialization never draws a second fortune.

DailyFortunes 0.2.1 decodes the envelope through the shared `FortuneRpcResponse` helper. An `error` takes priority over any payload, and a missing or malformed result fails before item delivery or acknowledgement. Local singleplayer/standalone backend calls retain their direct business results.

A draw is committed with `pending=true` before physical delivery. Delivery failures retain the same player/date/result/token; normal pending polling retries delivery and acknowledges it after inventory acceptance. Do not delete the record or reset daily eligibility to repair a wire-format failure.

Manager service 0.6.0.web.1 repairs currently running DailyFortunes 0.2.0 servers by restoring the compatible response fields. Only the independent web process needs restarting. DailyFortunes 0.2.1 can then be installed at a normal game restart for correct business-error reporting. This change has no database migration, frontend redesign, or Fabric game-code change.

Regression coverage: real HTTP/SQL draw response to the old and new decoders, repeated draw rejection, pending receipt identity, H2 draw/delivery acknowledgement, and malformed/error envelope rejection.
