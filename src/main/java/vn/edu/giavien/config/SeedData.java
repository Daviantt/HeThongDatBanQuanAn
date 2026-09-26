package vn.edu.giavien.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.FloorPlan;

@Component
public class SeedData implements CommandLineRunner {
  private final RestaurantRepository repo;
  private final PasswordEncoder passwords;
  private final boolean demo;

  public SeedData(
      RestaurantRepository repo, PasswordEncoder passwords, @Value("${app.demo}") boolean demo) {
    this.repo = repo;
    this.passwords = passwords;
    this.demo = demo;
  }

  @Override
  @Transactional
  public void run(String... args) {
    repo.lockSchedule();
    if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM restaurant_settings", Integer.class) == 0)
      repo.jdbc().update("INSERT INTO restaurant_settings VALUES(1,100000,15,15,10,22,15)");
    if (repo.tables().isEmpty()) {
      for (int i = 1; i <= 12; i++) {
        int row = (i - 1) / 3, col = (i - 1) % 3;
        repo.jdbc()
            .update(
                "INSERT INTO dining_table(id,code,zone,map_x,map_y,active) VALUES(?,?,?,?,?,TRUE)",
                i,
                "B%02d".formatted(i),
                row < 2 ? "Bên cửa sổ" : "Sân vườn",
                col,
                row);
      }
      for (int row = 0; row < 4; row++) {
        int a = row * 3 + 1;
        for (String combo :
            new String[] {
              a + "," + (a + 1), (a + 1) + "," + (a + 2), a + "," + (a + 1) + "," + (a + 2)
            }) repo.jdbc().update("INSERT INTO table_combination(table_ids) VALUES(?)", combo);
      }
    }
    migrateGardenLayout();
    addUpperFloor();
    clearEntrance();
    if (repo.dishes().isEmpty()) {
      dish(
          "Gỏi cuốn tôm thịt",
          "Tôm tươi, thịt luộc, rau thơm và sốt tương đậu phộng.",
          "Khai vị",
          65000,
          "rolls");
      dish(
          "Gỏi ngó sen",
          "Ngó sen giòn, tôm thịt và nước mắm chua ngọt.",
          "Khai vị",
          89000,
          "salad");
      dish(
          "Cá kho tộ",
          "Cá kho trong nồi đất, tiêu xanh và nước màu dừa.",
          "Món chính",
          129000,
          "fish");
      dish(
          "Gà nướng lá chanh",
          "Gà nướng thơm, lá chanh và muối ớt xanh.",
          "Món chính",
          159000,
          "chicken");
      dish(
          "Canh chua cá",
          "Vị chua thanh của me, dứa, cà chua và rau thơm.",
          "Món chính",
          99000,
          "soup");
      dish("Cơm niêu", "Cơm dẻo thơm, nấu chậm trong niêu đất.", "Món chính", 35000, "rice");
      dish(
          "Chè hạt sen",
          "Hạt sen mềm, đường phèn và nhãn thanh mát.",
          "Tráng miệng",
          45000,
          "dessert");
      dish("Trà sen", "Trà ướp sen dịu nhẹ, dùng nóng hoặc thêm đá.", "Đồ uống", 35000, "tea");
    }
    if (demo) {
      account("khach@moc.local", "Khách trải nghiệm", "0901234567", "CUSTOMER");
      account("nhanvien@moc.local", "Nhân viên GiaViên", "0901234568", "STAFF");
      account("admin@moc.local", "Quản lý GiaViên", "0901234569", "ADMIN");
    }
  }

  private void migrateGardenLayout() {
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM app_migration WHERE name='garden-layout-v1'", Integer.class)
        > 0) return;
    // Keep physical IDs and reservation links, including the two retired tables.
    repo.jdbc().update("UPDATE dining_table SET code='OLD-' || code WHERE floor=1");
    repo.jdbc().update("UPDATE dining_table SET active=FALSE,retired=TRUE WHERE id IN (5,8)");
    var remaining = repo.tables().stream().filter(t -> t.floor() == 1).toList();
    for (int i = 0; i < remaining.size(); i++) {
      var table = remaining.get(i);
      repo.jdbc()
          .update(
              "UPDATE dining_table SET code=?,zone=? WHERE id=?",
              "B%02d".formatted(i + 1),
              table.mapY() == 0 ? "Bên cửa sổ" : table.mapY() == 3 ? "Hiên nhà" : "Bên vườn",
              table.id());
    }
    repo.jdbc().update("DELETE FROM table_combination");
    var tables = repo.tables();
    for (int a = 0; a < tables.size(); a++) {
      for (int b = a + 1; b < tables.size(); b++) {
        addCombination(List.of(tables.get(a), tables.get(b)));
        for (int c = b + 1; c < tables.size(); c++) {
          addCombination(List.of(tables.get(a), tables.get(b), tables.get(c)));
        }
      }
    }
    repo.jdbc().update("INSERT INTO app_migration(name) VALUES('garden-layout-v1')");
  }

  private void addCombination(List<vn.edu.giavien.domain.Models.DiningTable> tables) {
    if (FloorPlan.canJoin(tables)) {
      repo.jdbc()
          .update(
              "INSERT INTO table_combination(table_ids) VALUES(?)",
              String.join(",", tables.stream().map(t -> Long.toString(t.id())).toList()));
    }
  }

  private void addUpperFloor() {
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM app_migration WHERE name='upper-floor-v1'", Integer.class)
        > 0) return;
    var ground = repo.tables().stream().filter(t -> t.floor() == 1).toList();
    long nextId = repo.jdbc().queryForObject("SELECT MAX(id) FROM dining_table", Long.class) + 1;
    for (int i = 0; i < ground.size(); i++) {
      var table = ground.get(i);
      repo.jdbc()
          .update(
              "INSERT INTO dining_table(id,code,zone,map_x,map_y,active,floor)"
                  + " VALUES(?,?,?,?,?,TRUE,2)",
              nextId + i,
              "B%02d".formatted(11 + i),
              table.mapY() == 0
                  ? "Bên cửa sổ tầng 2"
                  : table.mapY() == 3 ? "Ban công" : "Bên giếng trời",
              table.mapX(),
              table.mapY());
    }
    var upper = repo.tables().stream().filter(t -> t.floor() == 2).toList();
    for (int a = 0; a < upper.size(); a++) {
      for (int b = a + 1; b < upper.size(); b++) {
        addCombination(List.of(upper.get(a), upper.get(b)));
        for (int c = b + 1; c < upper.size(); c++) {
          addCombination(List.of(upper.get(a), upper.get(b), upper.get(c)));
        }
      }
    }
    repo.jdbc().update("INSERT INTO app_migration(name) VALUES('upper-floor-v1')");
  }

  private void clearEntrance() {
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM app_migration WHERE name='entrance-clearance-v1'",
                Integer.class)
        > 0) return;
    // Keep the table's identity and reservation history; retire only the entrance seat.
    var ids =
        repo.jdbc()
            .queryForList("SELECT id FROM dining_table WHERE code='B09' AND floor=1", Long.class);
    for (var combination : repo.combinations()) {
      if (combination.stream().anyMatch(ids::contains)) {
        repo.jdbc()
            .update(
                "DELETE FROM table_combination WHERE table_ids=?",
                String.join(",", combination.stream().map(String::valueOf).toList()));
      }
    }
    repo.jdbc()
        .update("UPDATE dining_table SET active=FALSE,retired=TRUE WHERE code='B09' AND floor=1");
    repo.jdbc().update("INSERT INTO app_migration(name) VALUES('entrance-clearance-v1')");
  }

  private void dish(
      String name, String description, String category, long price, String illustration) {
    repo.jdbc()
        .update(
            "INSERT INTO menu_item(name,description,category,price,available,illustration)"
                + " VALUES(?,?,?,?,TRUE,?)",
            name,
            description,
            category,
            price,
            illustration);
  }

  private void account(String email, String name, String phone, String role) {
    if (repo.account(email).isEmpty())
      repo.insertAccount(email, passwords.encode("MocDemo123!"), name, phone, role);
  }
}
