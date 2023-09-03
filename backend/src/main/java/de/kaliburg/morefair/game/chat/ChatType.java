package de.kaliburg.morefair.game.chat;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public enum ChatType {
  GLOBAL,
  LADDER(true),
  SYSTEM,
  MOD;

  private boolean parameterized = false;

  ChatType(boolean parameterized) {
    this.parameterized = parameterized;
  }
}
