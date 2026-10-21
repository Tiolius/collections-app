package app;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class WebController {

    private final Repo repo;

    public WebController(Repo repo) {
        this.repo = repo;
    }

    // ---------- Коллекции ----------

    @GetMapping("/")
    public String index(Model m) {
        m.addAttribute("collections", repo.allCollections());
        return "index";
    }

    @GetMapping("/collections/new")
    public String newCollectionForm(Model m) {
        return form(m, null, false, false, null, "Новая коллекция", "/");
    }

    @Transactional
    @PostMapping("/collections/new")
    public String createCollection(@RequestParam String name, @RequestParam(required = false) String type,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) MultipartFile image) {
        long id = repo.createCollection();
        repo.saveCollection(id, name, type, kind, image, false);
        return "redirect:/collections/" + id;
    }

    @GetMapping("/collections/{id}")
    public String collection(@PathVariable long id, Model m) {
        Entry c = repo.collection(id);
        m.addAttribute("c", c);
        m.addAttribute("digital", Repo.hasDigitalFields(c.kind()));
        m.addAttribute("items", repo.items(id));
        return "collection";
    }

    @GetMapping("/collections/{id}/image")
    public ResponseEntity<byte[]> collectionImage(@PathVariable long id) {
        Map<String, Object> row = repo.collectionImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) row.get("image_mime")))
                .body((byte[]) row.get("image"));
    }

    @GetMapping("/collections/{id}/edit")
    public String editCollectionForm(@PathVariable long id, Model m) {
        Entry c = repo.collection(id);
        return form(m, c, false, false, c.hasImage() ? "/collections/" + id + "/image" : null,
                "Редактировать коллекцию", "/collections/" + id);
    }

    @PostMapping("/collections/{id}/edit")
    public String editCollection(@PathVariable long id, @RequestParam String name,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String kind,
                                 @RequestParam(required = false) MultipartFile image,
                                 @RequestParam(name = "remove_image", required = false) String removeImage) {
        repo.collection(id); // 404, если нет
        repo.saveCollection(id, name, type, kind, image, removeImage != null);
        return "redirect:/collections/" + id;
    }

    @PostMapping("/collections/{id}/delete")
    public String deleteCollection(@PathVariable long id) {
        repo.delete("collections", id);
        return "redirect:/";
    }

    // ---------- Объекты ----------

    @GetMapping("/collections/{cid}/items/new")
    public String newItemForm(@PathVariable long cid, Model m) {
        boolean digital = Repo.hasDigitalFields(repo.collection(cid).kind());
        return form(m, null, true, digital, null, "Новый объект", "/collections/" + cid);
    }

    @Transactional
    @PostMapping("/collections/{cid}/items/new")
    public String createItem(@PathVariable long cid, @RequestParam String name,
                             @RequestParam(required = false) String platform,
                             @RequestParam(required = false) String format,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer price) {
        Entry c = repo.collection(cid);
        long id = repo.createItem(cid);
        repo.saveItem(id, c.kind(), name, platform, format, status, price);
        return "redirect:/collections/" + cid;
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(@PathVariable long id, Model m) {
        Entry it = repo.item(id);
        boolean digital = Repo.hasDigitalFields(repo.collection(it.collectionId()).kind());
        return form(m, it, true, digital, null, "Редактировать объект", "/collections/" + it.collectionId());
    }

    @PostMapping("/items/{id}/edit")
    public String editItem(@PathVariable long id, @RequestParam String name,
                           @RequestParam(required = false) String platform,
                           @RequestParam(required = false) String format,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer price) {
        Entry it = repo.item(id);
        repo.saveItem(id, repo.collection(it.collectionId()).kind(), name, platform, format, status, price);
        return "redirect:/collections/" + it.collectionId();
    }

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@PathVariable long id) {
        Entry it = repo.item(id);
        repo.delete("items", id);
        return "redirect:/collections/" + it.collectionId();
    }

    // ---------- вспомогательное ----------

    private String form(Model m, Entry obj, boolean isItem, boolean digital, String imgUrl, String title, String back) {
        m.addAttribute("obj", obj);
        m.addAttribute("isItem", isItem);
        m.addAttribute("digital", digital);
        m.addAttribute("kinds", Repo.KINDS);
        m.addAttribute("imgUrl", imgUrl);
        m.addAttribute("title", title);
        m.addAttribute("back", back);
        return "form";
    }
}
