package vn.edu.giavien.domain;

import java.util.Comparator;
import java.util.List;
import vn.edu.giavien.domain.Models.DiningTable;

/** Tables may join along an uninterrupted row or column, never across the courtyard. */
public final class FloorPlan {
  private FloorPlan() {}

  public static boolean canJoin(List<DiningTable> tables) {
    if (tables.size() < 2 || tables.size() > 3) return false;
    if (tables.stream().map(DiningTable::floor).distinct().count() != 1) return false;
    boolean horizontal = tables.stream().map(DiningTable::mapY).distinct().count() == 1;
    boolean vertical = tables.stream().map(DiningTable::mapX).distinct().count() == 1;
    if (!horizontal && !vertical) return false;
    var positions =
        tables.stream()
            .map(t -> horizontal ? t.mapX() : t.mapY())
            .sorted(Comparator.naturalOrder())
            .toList();
    for (int i = 1; i < positions.size(); i++) {
      if (positions.get(i) != positions.get(i - 1) + 1) return false;
    }
    return true;
  }
}
