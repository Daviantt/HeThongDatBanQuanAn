package vn.edu.giavien.web;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice(assignableTypes = {PageController.class, DemoPaymentController.class})
public class PageErrors {
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public String invalid(IllegalArgumentException error, Model model) {
    model.addAttribute("message", error.getMessage());
    return "error";
  }
}
