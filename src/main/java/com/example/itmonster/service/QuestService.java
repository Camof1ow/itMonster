package com.example.itmonster.service;

import com.example.itmonster.controller.request.QuestRequestDto;
import com.example.itmonster.controller.response.ClassDto;
import com.example.itmonster.controller.response.MainQuestResponseDto;
import com.example.itmonster.controller.response.QuestDetailResponseDto;
import com.example.itmonster.controller.response.QuestResponseDto;
import com.example.itmonster.controller.response.RecentQuestResponseDto;
import com.example.itmonster.controller.response.StackDto;
import com.example.itmonster.domain.Bookmark;
import com.example.itmonster.domain.Channel;
import com.example.itmonster.domain.Member;
import com.example.itmonster.domain.MemberInChannel;
import com.example.itmonster.domain.Offer;
import com.example.itmonster.domain.QQuest;
import com.example.itmonster.domain.Quest;
import com.example.itmonster.domain.Squad;
import com.example.itmonster.domain.StackOfQuest;
import com.example.itmonster.exceptionHandler.CustomException;
import com.example.itmonster.exceptionHandler.ErrorCode;
import com.example.itmonster.repository.BookmarkRepository;
import com.example.itmonster.repository.CommentRepository;
import com.example.itmonster.repository.MemberInChannelRepository;
import com.example.itmonster.repository.OfferRepository;
import com.example.itmonster.repository.QuestRepository;
import com.example.itmonster.repository.SquadRepository;
import com.example.itmonster.repository.StackOfQuestRepository;
import com.example.itmonster.security.UserDetailsImpl;
import com.example.itmonster.socket.MessageResponseDto;
import com.example.itmonster.utils.SearchPredicate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestService {

    private final QuestRepository questRepository;
    private final SquadRepository squadRepository;
    private final BookmarkRepository bookmarkRepository;
    private final CommentRepository commentRepository;
    private final StackOfQuestRepository stackOfQuestRepository;
    private final MemberInChannelRepository memberInChannelRepository;
    private final ChannelService channelService;
    private final OfferRepository offerRepository;

    @PersistenceContext
    private EntityManager em;

    @Transactional // 게시글 작성 // 기술스택 추가해야됨 !!
    public boolean createQuest(QuestRequestDto questRequestDto, UserDetailsImpl userDetails) {
        Member member = userDetails.getMember();
        Quest quest = Quest.builder()
            .member(member)
            .title(questRequestDto.getTitle())
            .content(questRequestDto.getContent())
            .frontend(questRequestDto.getFrontend())
            .backend(questRequestDto.getBackend())
            .fullstack(questRequestDto.getFullstack())
            .designer(questRequestDto.getDesigner())
            .status(questRequestDto.getBackend() + questRequestDto.getFrontend()
                + questRequestDto.getDesigner() + questRequestDto.getFullstack() == 0)
            .isComplete(false)
            .duration(questRequestDto.getDuration())
            .build();
        questRepository.save(quest);

        // 퀘스트의 스택 저장
        saveStack(quest, questRequestDto);

        squadRepository.save(Squad.builder()  // 본인을 포함하여 Squad 생성
            .quest(quest)
            .member(member)
            .build());

        Channel channel = channelService.createChannel(quest); // 대화방 생성

        memberInChannelRepository.save(MemberInChannel.builder()  // 대화방에 본인을 매칭
            .member(member)
            .channel(channel)
            .build());

        return true;
    }

    @Transactional(readOnly = true) // 모든 게시글 최신순 조회 // 기술스택 추가해야됨 !!
    public List<QuestResponseDto> readAllQuest() {
        List<Quest> quests = questRepository.findAllByOrderByModifiedAtDesc();
        List<QuestResponseDto> result = new ArrayList<>();
        for (Quest quest : quests) {
            result.add(toQuestResponseDto(quest));
        }
        return result;
    }

//    @Cacheable(value = "favoriteQuestCaching")
    @Transactional(readOnly = true) // 메인페이지용 북마크 높은 3개 조회 // 기술스택 추가해야됨 !!
    public List<MainQuestResponseDto> readFavorite3Quest() {
        List<Quest> quests = questRepository.findTop3ByIsCompleteFalseOrderByBookmarkCntDesc();
        List<MainQuestResponseDto> result = new ArrayList<>();
        for (Quest quest : quests) {
            result.add(toMainQuestResponseDto(quest));
        }
        return result;
    }

    @Transactional(readOnly = true) // 메인페이지용 게시글 최신순 3개 조회 // 기술스택 추가해야됨 !!
    public List<RecentQuestResponseDto> readRecent3Quest() {
        List<Quest> quests = questRepository.findTop3ByIsCompleteFalseOrderByModifiedAtDesc();
        List<RecentQuestResponseDto> result = new ArrayList<>();
        for (Quest quest : quests) {
            result.add(toRecentQuestResponseDto(quest));
        }
        return result;
    }

    @Transactional(readOnly = true) // 게시글 상세 조회 // 댓글조회, 기술스택 추가해야됨 !!
    public QuestDetailResponseDto readQuest(Long questId) {
        Quest quest = validateQuest(questId);
        return toQuestDetailsResponseDto( quest );
    }

    @Transactional // 게시글 수정 // 기술스택 추가해야됨 !!
    public boolean updateQuest(Long questId, QuestRequestDto questRequestDto,
        UserDetailsImpl userDetails) {
        Member member = userDetails.getMember();
        Quest quest = validateQuest(questId);
        if (validateAuthor(member, quest)) {
            quest.updateQuest(questRequestDto.getTitle(), questRequestDto.getContent(),
                questRequestDto.getFrontend(), questRequestDto.getBackend(),
                questRequestDto.getFullstack(), questRequestDto.getDesigner(),
                questRequestDto.getDuration());

            stackOfQuestRepository.deleteByQuest(quest);
            saveStack(quest, questRequestDto);
        }
        return true;
    }

    @Transactional // 게시글 삭제
    public boolean deleteQuest(Long questId, UserDetailsImpl userDetails) {
        Member member = userDetails.getMember();
        Quest quest = validateQuest(questId);
        if (validateAuthor(member, quest)) {
            questRepository.deleteById(questId);
        }
        return true;
    }

    @Transactional
    public boolean completeQuest(Long questId, UserDetailsImpl userDetails) {
        Member member = userDetails.getMember();
        Quest quest = validateQuest(questId);
        if(!Objects.equals(quest.getMember().getId(), member.getId())) {
            throw new CustomException(ErrorCode.INVALID_AUTHORITY);
        }  // 퀘스트 작성자만 완료할 수 있음
        quest.completeQuest();
        return true;
    }

    @Transactional // 게시글 북마크
    public boolean bookmarkQuest(Long questId, UserDetailsImpl userDetails) {
        Member member = userDetails.getMember();
        Quest quest = validateQuest(questId);
        if (!bookmarkRepository.existsByMarkedMemberAndQuest(member, quest)) {
            bookmarkRepository.save(Bookmark.builder()
                .markedMember(member)
                .quest(quest)
                .build());
            return true;
        }
        bookmarkRepository.deleteByMarkedMemberAndQuest(member, quest);
        return false;
    }

    // 필터링된 검색결과 가져오기
    @Transactional(readOnly = true)
    public List<QuestResponseDto> searchQuests(MultiValueMap<String, String> allParameters ) {

        JPAQueryFactory jpaQueryFactory = new JPAQueryFactory(em);

        List<Quest> quests = SearchPredicate.filterSearch(allParameters, jpaQueryFactory);

        List<QuestResponseDto> result = new ArrayList<>();
        for (Quest quest : quests) {
            result.add(toQuestResponseDto(quest));
        }
        return result;
    }

    // 필터링된 검색결과 pageable 가져오기
    @Transactional(readOnly = true)
    public Page<QuestResponseDto> searchQuestsPage(MultiValueMap<String, String> allParameters, Pageable pageable ) {

        JPAQueryFactory jpaQueryFactory = new JPAQueryFactory(em);

        Page<Quest> quests = SearchPredicate.filterSearchPage(allParameters, jpaQueryFactory, pageable);

        return new PageImpl<>(
            quests.stream().map(this::toQuestResponseDto).collect(Collectors.toList()),
            pageable, quests.getTotalElements());
    }



    private Quest validateQuest(Long questId) {
        return questRepository.findById(questId)
            .orElseThrow(() -> new CustomException(ErrorCode.QUEST_NOT_FOUND));
    }

    private boolean validateAuthor(Member member, Quest quest) { // 수정,삭제 권한 확인(글쓴이인지 확인)
        if (!member.getId().equals(quest.getMember().getId())) {
            throw new CustomException(ErrorCode.INVALID_AUTHORITY);
        }
        return true;
    }

    private QuestResponseDto toQuestResponseDto(Quest quest) {
        List<StackDto> stackDtos = quest.getStacks().stream().map(StackDto::new)
            .collect(Collectors.toList());
        List<String> temp = new ArrayList<>();
        for (StackDto stackDto : stackDtos) {
            temp.add(stackDto.getStackName());
        }
        return QuestResponseDto.builder()
            .questId(quest.getId())
            .memberId(quest.getMember().getId())
            .title(quest.getTitle())
            .nickname(quest.getMember().getNickname())
            .content(quest.getContent())
            .duration(quest.getDuration())
            .status(quest.getStatus())
            .isComplete(quest.getIsComplete())
            .profileImg(quest.getMember().getProfileImg())
            .classes(new ClassDto(quest))
            .bookmarkCnt(bookmarkRepository.countAllByQuest(quest))
            .commentCnt(commentRepository.countAllByQuest(quest))
            .createdAt(quest.getCreatedAt())
            .modifiedAt(quest.getModifiedAt())
            .stacks(temp)
            .build();
    }

    private QuestDetailResponseDto toQuestDetailsResponseDto( Quest quest ) {
        List<StackDto> stackDtos = quest.getStacks().stream().map(StackDto::new)
            .collect(Collectors.toList());
        List<String> temp = new ArrayList<>();
        for (StackDto stackDto : stackDtos) {
            temp.add(stackDto.getStackName());
        }
        List<Offer> offers = offerRepository.findAllByQuest( quest );
        List<Long> offeredMembers = new ArrayList<>();
        offers.forEach( offer -> offeredMembers.add ( offer.getOfferedMember().getId() ) );
        return QuestDetailResponseDto.builder()
            .questId(quest.getId())
            .memberId(quest.getMember().getId())
            .title(quest.getTitle())
            .nickname(quest.getMember().getNickname())
            .content(quest.getContent())
            .duration(quest.getDuration())
            .status(quest.getStatus())
            .isComplete(quest.getIsComplete())
            .profileImg(quest.getMember().getProfileImg())
            .classes(new ClassDto(quest))
            .bookmarkCnt(bookmarkRepository.countAllByQuest(quest))
            .commentCnt(commentRepository.countAllByQuest(quest))
            .createdAt(quest.getCreatedAt())
            .modifiedAt(quest.getModifiedAt())
            .stacks(temp)
            .offeredMember( offeredMembers )
            .build();
    }

    // 0915 수정추가분
    private MainQuestResponseDto toMainQuestResponseDto(Quest quest) {
        List<StackDto> stackDtos = quest.getStacks().stream().map(StackDto::new)
            .collect(Collectors.toList());
        List<String> temp = new ArrayList<>();
        for (StackDto stackDto : stackDtos) {
            temp.add(stackDto.getStackName());
        }
        return MainQuestResponseDto.builder()
            .mainQuestId(quest.getId())
            .title(quest.getTitle())
            .nickname(quest.getMember().getNickname())
            .profileImg(quest.getMember().getProfileImg())
            .content(quest.getContent())
            .duration(quest.getDuration())
            .status(quest.getStatus())
            .isComplete(quest.getIsComplete())
            .classes(new ClassDto(quest))
            .bookmarkCnt(bookmarkRepository.countAllByQuest(quest))
            .commentCnt(commentRepository.countAllByQuest(quest))
	        .createdAt( quest.getCreatedAt() )
            .stacks(temp)
            .build();
    }

    private RecentQuestResponseDto toRecentQuestResponseDto(Quest quest) {
        List<StackDto> stackDtos = quest.getStacks().stream().map(StackDto::new)
            .collect(Collectors.toList());
        List<String> temp = new ArrayList<>();
        for (StackDto stackDto : stackDtos) {
            temp.add(stackDto.getStackName());
        }
        return RecentQuestResponseDto.builder()
            .recentQuestId(quest.getId())
            .title(quest.getTitle())
            .nickname(quest.getMember().getNickname())
            .profileImg(quest.getMember().getProfileImg())
            .content(quest.getContent())
            .duration(quest.getDuration())
            .status(quest.getStatus())
            .isComplete(quest.getIsComplete())
            .classes(new ClassDto(quest))
            .bookmarkCnt(bookmarkRepository.countAllByQuest(quest))
            .commentCnt(commentRepository.countAllByQuest(quest))
            .createdAt(quest.getCreatedAt())
            .modifiedAt(quest.getModifiedAt())
            .stacks(temp)
            .build();
    }

    private void saveStack(Quest quest, QuestRequestDto questRequestDto) {
        List<String> stacks = questRequestDto.getStacks();
        for (String stack : stacks) {
            stackOfQuestRepository.save(
                StackOfQuest.builder()
                    .stackName(stack)
                    .quest(quest)
                    .build());
        }
    }

    @CacheEvict(value = "favoriteQuestCaching", allEntries = true)
    @Scheduled(cron = "0 0 0 * * *")
    public void deleteCache() {
    }
}
