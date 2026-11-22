package az.borcdefteri.ads;

import az.borcdefteri.ads.AdStoryService.AdminView;
import az.borcdefteri.ads.AdStoryService.Input;
import az.borcdefteri.ads.AdStoryService.StoryView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Reklam hekayələri: the phone reads live stories; the platform admin manages them. */
@RestController
public class AdStoryController {

    private final AdStoryService stories;

    public AdStoryController(AdStoryService stories) {
        this.stories = stories;
    }

    @GetMapping("/api/ads")
    public List<StoryView> live() {
        return stories.live();
    }

    /** The picture changes only with a new image_version (the phone adds ?v=), so it may be cached. */
    @GetMapping("/api/ads/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        AdStoryService.Image img = stories.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(img.content());
    }

    @PostMapping("/api/ads/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@PathVariable UUID id) {
        stories.view(id);
    }

    @PostMapping("/api/ads/{id}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void click(@PathVariable UUID id) {
        stories.click(id);
    }

    @GetMapping("/api/admin/ads")
    public List<AdminView> all() {
        return stories.all();
    }

    @PostMapping("/api/admin/ads")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminView create(@RequestBody Input in) {
        return stories.create(in);
    }

    @PutMapping("/api/admin/ads/{id}")
    public AdminView update(@PathVariable UUID id, @RequestBody Input in) {
        return stories.update(id, in);
    }

    @PostMapping(value = "/api/admin/ads/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminView setImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return stories.setImage(id, file);
    }

    @DeleteMapping("/api/admin/ads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        stories.delete(id);
    }
}
