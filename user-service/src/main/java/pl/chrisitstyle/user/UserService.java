package pl.chrisitstyle.user;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chrisitstyle.user.exception.UserAlreadyExistsException;
import pl.chrisitstyle.user.exception.UserNotFoundException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String nickname = request.nickname().trim();
        String email = request.email().trim();

        if (userRepository.existsByNicknameIgnoreCase(nickname)) {
            throw new UserAlreadyExistsException(
                    "User with nickname '" + nickname + "' already exists"
            );
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new UserAlreadyExistsException(
                    "User with email '" + email + "' already exists"
            );
        }

        User user = new User();
        user.setNickname(nickname);
        user.setEmail(email);
        user.setActive(true);

        User savedUser = userRepository.save(user);

        return toResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAll() {
        return userRepository.findAllByActiveTrue()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        User user = findById(id);

        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = findById(id);

        String nickname = request.nickname().trim();
        String email = request.email().trim();

        if (!user.getNickname().equalsIgnoreCase(nickname)
                && userRepository.existsByNicknameIgnoreCase(nickname)) {
            throw new UserAlreadyExistsException(
                    "User with nickname '" + nickname + "' already exists"
            );
        }

        if (!user.getEmail().equalsIgnoreCase(email)
                && userRepository.existsByEmailIgnoreCase(email)) {
            throw new UserAlreadyExistsException(
                    "User with email '" + email + "' already exists"
            );
        }

        user.setNickname(nickname);
        user.setEmail(email);
        user.setActive(request.active());

        return toResponse(user);
    }

    @Transactional
    public void delete(Long id) {
        User user = findById(id);
        user.setActive(false);
    }

    private User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                user.getActive(),
                user.getCreatedAt()
        );
    }
}