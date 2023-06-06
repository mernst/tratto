python run_classifier.py \
--do_train \
--do_eval \
--do_predict \
--model_type roberta \
--model_name_or_path microsoft/codebert-base \
--tokenizer_name roberta-base \
--tratto_model_type token_values \
--task_name tokenValues_classifier \
--max_seq_length 512 \
--batch_size 32 \
--learning_rate 1e-5 \
--num_epochs 8 \
--save_steps 200 \
--accumulation_steps 1 \
--test_ratio 0.1 \
--validation_ratio 0.1 \
--data_dir ./dataset/token-values-dataset \
--output_dir ./output_token_values \
--classification_type label_prediction