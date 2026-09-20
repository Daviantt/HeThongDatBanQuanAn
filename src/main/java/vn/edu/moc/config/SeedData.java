package vn.edu.moc.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.moc.data.RestaurantRepository;

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
                "INSERT INTO dining_table VALUES(?,?,?,?,?,TRUE)",
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
      account("nhanvien@moc.local", "Nhân viên Mộc", "0901234568", "STAFF");
      account("admin@moc.local", "Quản lý Mộc", "0901234569", "ADMIN");
    }
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
